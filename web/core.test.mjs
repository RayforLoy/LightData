import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import {
  FORMATS,
  APERTURES,
  SHUTTERS,
  ISOS,
  field,
  mm,
  exposureEV,
  solve,
  equivalentExposures,
  srgbToLinear,
  linearToSrgb,
  luma,
  scaleSrgbCode,
  defaultResponse,
  histogram,
  meter,
  spot,
  positive,
  density,
  renderImage,
  loadFilms,
  spectralMatrix,
  reciprocity,
  CustomPresets,
  customFilm,
} from "./core.js";

const rawFilms = JSON.parse(
  await readFile(new URL("./data/films.json", import.meta.url), "utf8"),
);
const originalFetch = globalThis.fetch;
globalThis.fetch = async () => new Response(JSON.stringify(rawFilms));
const films = await loadFilms();
globalThis.fetch = originalFetch;
const approx = (actual, expected, tolerance = 1e-8) =>
  assert.ok(
    Math.abs(actual - expected) <= tolerance,
    `${actual} ≠ ${expected}`,
  );
const image = (w, h, pixel) => ({
  width: w,
  height: h,
  data: Uint8ClampedArray.from(
    Array.from({ length: w * h }, (_, i) => pixel(i)).flat(),
  ),
});

test("physical film gate and inch conversion match the 24 mm full-frame reference", () => {
  assert.equal(FORMATS.length, 17);
  const view = field(36, 24, 24);
  approx((2 * Math.atan(view.x) * 180) / Math.PI, 73.7398, 0.001);
  approx((2 * Math.atan(view.y) * 180) / Math.PI, 53.1301, 0.001);
  approx(mm(4, true), 101.6);
});

test("A/S/M preserve user controls, ISO 3 and fractional ISO across ±5 EV compensation", () => {
  assert.equal(ISOS[0], 3);
  for (const iso of [3, 6.25, 100, 800]) {
    const manual = { t: 1 / 60, a: 4, iso };
    for (const mode of ["A", "S"]) {
      const shot = solve(mode, manual, 12);
      assert.equal(shot.iso, iso);
      assert.equal(
        shot[mode === "A" ? "a" : "t"],
        manual[mode === "A" ? "a" : "t"],
      );
      approx(exposureEV(shot), 12, 0.23);
    }
    assert.deepEqual(solve("M", manual, 15, 5), manual);
  }
  const manual = { t: 1 / 125, a: 5.6, iso: 100 };
  for (let thirds = -15; thirds <= 15; thirds++)
    for (const mode of ["A", "S"]) {
      const shot = solve(mode, manual, 12, thirds / 3);
      approx(exposureEV(shot), 12 - thirds / 3, 0.2);
    }
  assert.equal(solve("A", manual, -20).t, SHUTTERS.at(-1));
  assert.throws(() => solve("P", manual, 12));
});

test("equivalent columns share the exact center and omit impossible shutter gaps", () => {
  const center = { t: 1 / 125, a: 8, iso: 37.5 },
    pairs = equivalentExposures(center);
  assert.deepEqual(
    pairs.map((p) => p.a),
    [1, 1.4, 2, 2.8, 4, 5.6, 8, 11, 16, 22, 32, 45, 64, 90],
  );
  assert.equal(
    pairs.find((p) => p.a === 8),
    center,
  );
  assert.equal(pairs.find((p) => p.a === 5.6).t, 1 / 250);
  for (const a of APERTURES)
    for (const t of SHUTTERS) {
      const anchor = { t, a, iso: 3 };
      for (const p of equivalentExposures(anchor))
        approx(exposureEV(p), exposureEV(anchor), 0.180001);
    }
  assert.ok(
    !equivalentExposures({ t: 50, a: 8, iso: 100 }).some((p) => p.a === 11),
  );
});

test("all bright stripe phases, widths and 90 degree rotation count every valid pixel", () => {
  for (const [w, h] of [
    [4, 4],
    [12, 5],
    [5, 12],
  ])
    for (let phase = 0; phase < 4; phase++) {
      const frame = image(w, h, (i) =>
        (w % 4 === 0 ? i % w : Math.floor(i / w)) % 4 === phase
          ? [255, 255, 255, 255]
          : [117, 117, 117, 255],
      );
      const hist = histogram(frame);
      assert.equal(hist[255], (w * h) / 4);
      assert.equal(hist[117], (w * h * 3) / 4);
      approx(meter(hist), 1.090915, 0.00001);
    }
});

test("spot and histogram ignore unfilled panorama pixels and share absolute coordinates", () => {
  const frame = image(20, 20, (i) =>
    i % 20 < 8 ? [0, 0, 0, 0] : [117, 117, 117, 255],
  );
  const hist = histogram(frame);
  assert.equal(
    hist.reduce((a, b) => a + b),
    240,
  );
  approx(spot(frame, 0.5, 0.5, defaultResponse, 12), 12 + defaultResponse[117]);
  approx(meter(hist), defaultResponse[117]);
  assert.ok(Number.isNaN(spot(frame, 0.1, 0.5)));
});

test("capture metadata cancels AE changes and target ISO moves Zone V by one stop", () => {
  const base = { t: 1 / 64, a: 4, iso: 100 },
    reference = exposureEV(base);
  for (const capture of [
    base,
    { ...base, t: 1 / 128 },
    { ...base, iso: 200 },
  ]) {
    const gray = Math.round(
      255 * linearToSrgb(0.18 * 2 ** (reference - exposureEV(capture))),
    );
    const frame = image(16, 16, () => [gray, gray, gray, 255]);
    approx(exposureEV(capture) + meter(histogram(frame)), reference, 0.025);
    approx(
      spot(frame, 0.5, 0.5, defaultResponse, exposureEV(capture)),
      reference,
      0.025,
    );
  }
  approx(exposureEV({ ...base, iso: 200 }), reference - 1);
});

test("linear primary luminance and exposure gain preserve dark blue", () => {
  approx(defaultResponse[luma(0, 0, 255)], Math.log2(0.0722 / 0.18), 0.025);
  assert.equal(scaleSrgbCode(32, 8), 95);
  assert.equal(luma(0, 0, 95), 22);
  for (let i = 0; i < 256; i++)
    approx(linearToSrgb(srgbToLinear(i / 255)), i / 255);
});

test("168 catalogue records retain 122 measured curves, 428 spectra and 477 layer samples", () => {
  assert.equal(films.length, 168);
  assert.equal(new Set(films.map((f) => f.id)).size, 168);
  assert.equal(
    films.filter((f) => f.info.startsWith("厂家图采样")).length,
    122,
  );
  assert.equal(
    films.reduce(
      (n, f) => n + Object.values(f.color?.spectral || {}).flat().length,
      0,
    ),
    428,
  );
  assert.equal(
    films.reduce(
      (n, f) => n + Object.values(f.color?.density || {}).flat().length,
      0,
    ),
    477,
  );
  for (const film of films) {
    assert.ok(
      film.iso > 0 &&
        film.low < 0 &&
        film.high > 0 &&
        /^https:\/\//.test(film.url),
    );
    assert.ok(film.dots.every((p, i, a) => !i || p[0] > a[i - 1][0]));
    approx(positive(film, 0), 0.18);
    assert.ok(positive(film, -2) < positive(film, 2));
    assert.equal(density(film, -100), film.dots[0][1]);
    if (film.color)
      for (const row of spectralMatrix(film.color.spectral))
        approx(
          row.reduce((a, b) => a + b),
          1,
        );
  }
});

test("wide-latitude negatives retain visible midtone separation in the viewed print", () => {
  for (const name of ["PRO 160S", "PORTRA 400", "HP5 PLUS"]) {
    const film = films.find(f => f.name === name);
    const gray = ev => Math.round(255 * linearToSrgb(positive(film, ev)));
    assert.equal(gray(0), 118);
    assert.ok(gray(-2) < 70, name);
    assert.ok(gray(2) > 180, name);
    assert.ok(gray(3) > gray(2) && gray(-3) < gray(-2), name);
  }
});

test("display filters never mutate input and spectra-only ULTRAMAX falls back to luma safely", () => {
  const frame = image(3, 2, (i) => [i * 20, 117, 40, 255]),
    original = frame.data.slice();
  for (const style of ["color", "bw", "aero", "purple"])
    for (const name of ["EKTAR 100", "ULTRAMAX 400"]) {
      const rendered = renderImage(frame, {
        film: films.find((f) => f.name === name),
        preview: true,
        style,
      });
      assert.equal(rendered.data.length, 24);
      assert.deepEqual(frame.data, original);
    }
  const gray = Math.round(255 * linearToSrgb(0.18)),
    neutral = image(1, 1, () => [gray, gray, gray, 255]);
  for (const film of films.filter((f) => f.color?.density.red)) {
    const out = renderImage(neutral, { film, preview: true, stripes: false });
    for (let i = 0; i < 3; i++) approx(out.data[i], gray, 1);
  }
});

test("negative and reversal preview both increase viewed brightness with exposure", () => {
  for (const name of ["PRO 160S", "EKTACHROME E100"]) {
    const film = films.find((f) => f.name === name),
      frame = image(1, 1, () => [117, 117, 117, 255]);
    const values = [-2, 0, 2].map(
      (ev) =>
        renderImage(frame, {
          film,
          preview: true,
          cameraEv: ev,
          targetEv: 0,
          stripes: false,
        }).data[0],
    );
    assert.ok(values[0] < values[1] && values[1] < values[2]);
  }
});

test("published reciprocity formula, discrete table and unknown values keep the measured time independent", () => {
  approx(reciprocity("HP5 PLUS", 10).seconds, 20.4, 0.05);
  approx(reciprocity("Phoenix II", 10).seconds, 20.4, 0.05);
  assert.ok(reciprocity("Phoenix II", 51).note.includes("公式外推"));
  assert.ok(!reciprocity("Phoenix II", 50).note.includes("公式外推"));
  assert.equal(reciprocity("FOMAPAN 100 Classic", 10).seconds, 80);
  assert.equal(reciprocity("FOMAPAN 100 Classic", 11).seconds, null);
  assert.equal(reciprocity("PORTRA 400", 1).seconds, 1);
  const portra = reciprocity("PORTRA 400", 60);
  approx(portra.seconds, 60 ** 1.3);
  assert.equal(portra.network, true);
  assert.equal(portra.extrapolated, true);
  assert.match(portra.source, /metergeist\.com/);
  assert.ok(reciprocity("EKTACHROME E100", 120).note.includes("CC10R"));
  for (const t of [0, -1, Infinity, NaN, Number.MAX_VALUE])
    assert.equal(reciprocity("HP5 PLUS", t).seconds, null);
});

test("exact film names, manual priority and anchored network interpolation", () => {
  assert.equal(reciprocity("PRO 160S", 2).seconds, 2);
  assert.match(reciprocity("PRO 160S", 4).note, /1\/3/);
  assert.match(reciprocity("PRO 160S", 5).note, /不建议/);
  assert.equal(reciprocity("VELVIA 100", 60).network, undefined);
  assert.match(reciprocity("VELVIA 100", 120).note, /2\.5M/);
  const gap = reciprocity("VELVIA 100", Math.sqrt(60 * 120));
  approx(gap.seconds, Math.sqrt(60 * 152));
  assert.equal(gap.extrapolated, false);
  assert.equal(gap.network, true);
  assert.equal(reciprocity("PORTRA 400 custom", 60).seconds, null);
  assert.equal(reciprocity("toString", 60).seconds, null);
  assert.match(reciprocity("Phoenix II", 10).source, /harmanphoto\.co\.uk/);
  assert.equal(reciprocity("Phoenix II", 55).extrapolated, true);
});

test("custom JSON roundtrip is Android compatible, UUID merge is stable and invalid imports are atomic", () => {
  const preset = {
    id: "70578e74-00c8-441c-9372-27d118f2c0c9",
    name: '夜景 "测试"',
    digital: false,
    total: 12,
    above: 4,
    iso: 12.5,
  };
  const json = CustomPresets.encode([preset]);
  assert.deepEqual(CustomPresets.decode(json), [preset]);
  const updated = { ...preset, iso: 160 };
  assert.deepEqual(CustomPresets.merge([preset], [updated]), [updated]);
  approx(customFilm(preset).low, -8);
  approx(positive(customFilm(preset), 0), 0.18);
  for (const altered of [
    { ...preset, iso: 0 },
    { ...preset, iso: "100" },
    { ...preset, total: 33 },
    { ...preset, above: 12 },
    { ...preset, id: "not-a-uuid" },
    { ...preset, name: " " },
    { ...preset, digital: "false" },
  ])
    assert.throws(() => CustomPresets.encode([altered]));
  assert.throws(() =>
    CustomPresets.decode(
      json.replace('"iso": 12.5', '"iso": 12.5, "iso": 400'),
    ),
  );
  assert.throws(() =>
    CustomPresets.decode(json.replace('"version": 1', '"version": 2')),
  );
  assert.throws(() => CustomPresets.merge([preset], [preset, preset]));
  assert.deepEqual(preset.iso, 12.5);
});


test("unobserved pixels give no meter value, including a missing spot beside valid samples", () => {
  const empty = { width: 3, height: 3, data: new Uint8ClampedArray(36) };
  assert.ok(Number.isNaN(meter(histogram(empty))));
  const partlyObserved = { ...empty, data: new Uint8ClampedArray(36).fill(128) };
  for (let i = 3; i < 36; i += 4) partlyObserved.data[i] = 255;
  partlyObserved.data[4 * 4 + 3] = 0;
  assert.ok(Number.isFinite(meter(histogram(partlyObserved))));
  assert.ok(Number.isNaN(spot(partlyObserved, .5, .5)));
  assert.ok(Number.isFinite(spot(partlyObserved, 0, 0)));
});

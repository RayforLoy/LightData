import assert from "node:assert/strict";
import test from "node:test";
import {
  FilmLutLibrary,
  createFilmLutRenderer,
  renderFilmLutCPU,
} from "./film-lut.js";
import { defaultResponse, luma, positive, srgbToLinear } from "./core.js";

const film = {
  low: -2,
  high: 2,
  dots: [
    [-8, 0.1],
    [0, 0.8],
    [8, 2.9],
  ],
};
const lut = new Uint8Array(33 ** 3 * 3);
for (let b = 0; b < 33; b++)
  for (let g = 0; g < 33; g++)
    for (let r = 0; r < 33; r++) {
      const p = 3 * (r + 33 * g + 33 ** 2 * b);
      lut.set(
        [
          Math.round((r * 255) / 32),
          Math.round((g * 255) / 32),
          Math.round((b * 255) / 32),
        ],
        p,
      );
    }
const pixel = (r, g, b, a = 255) => ({
  width: 1,
  height: 1,
  data: new Uint8ClampedArray([r, g, b, a]),
});
const options = { film, lut, preview: true, stripes: false };
function brightness(data) {
  return [0.2126, 0.7152, 0.0722].reduce(
    (v, w, i) => v + w * srgbToLinear(data[i] / 255),
    0,
  );
}

function bounds(ev) {
  const low = positive(film, film.low),
    high = positive(film, film.high);
  return ev < film.low ? [0, low] : ev > film.high ? [high, 1] : [low, high];
}

test("Monochrome LUT preserves different color responses at equal metered EV within each film zone", () => {
  const mono = new Uint8Array(lut.length);
  for (let i = 0; i < mono.length; i += 3) mono.fill(lut[i], i, i + 3);
  const red = [200, 0, 0],
    green = [0, 115, 0];
  assert.equal(luma(...red), luma(...green));
  for (const ev of [-4, 0, 4]) {
    const opts = {
      ...options,
      lut: mono,
      response: new Float64Array(256).fill(ev),
    };
    const outputs = [red, green].map(
      (rgb) => renderFilmLutCPU(pixel(...rgb), opts).data,
    );
    const [brighter, darker] = outputs.map(brightness);
    const [lo, hi] = bounds(ev);
    assert.ok(brighter > darker + 0.01);
    assert.ok(darker >= lo - 0.004 && brighter <= hi + 0.004);
    for (const rgb of outputs)
      assert.ok(rgb[0] === rgb[1] && rgb[1] === rgb[2]);
  }
});
test("Calibration anchors output luminance, preserving the LUT RGB axes and raw input", () => {
  const response = Float64Array.from(defaultResponse, (v) => v + 0.75);
  for (const channels of [
    [255, 0, 0],
    [0, 240, 0],
    [5, 0, 210],
    [113, 157, 93],
    [0, 0, 0],
    [25, 25, 25],
    [119, 119, 119],
    [230, 230, 230],
  ]) {
    const img = pixel(...channels);
    for (const delta of [-1, 0, 2]) {
      const { data } = renderFilmLutCPU(img, {
        ...options,
        response,
        cameraEv: delta,
      });
      const ev = response[luma(...channels)] + delta;
      if (channels[0] === channels[1] && channels[1] === channels[2]) {
        assert.ok(Math.abs(brightness(data) - positive(film, ev)) < 0.005);
      } else {
        const [lo, hi] = bounds(ev);
        assert.ok(
          brightness(data) >= lo - 0.005 && brightness(data) <= hi + 0.005,
        );
      }
      if (channels.filter((c) => c === 0).length === 2) {
        assert.equal(
          data.indexOf(Math.max(...data.slice(0, 3))),
          channels.indexOf(Math.max(...channels)),
        );
      }
    }
    assert.deepEqual(Array.from(img.data), [...channels, 255]);
  }
});
test("Calibrated EV rescales the original linear channels before logarithmic LUT indexing", () => {
  const [r, g, b] = [87, 121, 164],
    image = pixel(r, g, b);
  const response = Float64Array.from({ length: 256 }, () => -1);
  const output = renderFilmLutCPU(image, {
    ...options,
    response,
    cameraEv: 3,
    targetEv: 1,
  }).data;
  const linear = [r, g, b].map((c) => srgbToLinear(c / 255));
  const weighted = (rgb) => rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
  const exposure = 0.18 * 2;
  function expectedSample(c) {
    const x = (Math.log2(1 + c / 0.18) * 32) / 9;
    const lo = Math.floor(x),
      f = x - lo;
    return srgbToLinear(
      ((1 - f) * Math.round((lo * 255) / 32) +
        f * Math.round(((lo + 1) * 255) / 32)) /
        255,
    );
  }
  const mapped = linear.map((c) =>
    expectedSample((c * exposure) / weighted(linear)),
  );
  const [lo, hi] = bounds(1);
  const target = Math.max(
    lo,
    Math.min(
      hi,
      (positive(film, 1) * (weighted(mapped) + 0.0001)) /
        (expectedSample(exposure) + 0.0001),
    ),
  );
  const actual = Array.from(output.slice(0, 3), (c) => srgbToLinear(c / 255));
  for (let i = 0; i < 3; i++)
    assert.ok(
      Math.abs(actual[i] - (mapped[i] * target) / weighted(mapped)) < 0.004,
    );
});
test("Styles preserve curve luminance; stripe classification reads pre-LUT EV", () => {
  const im = pixel(180, 110, 35);
  const expected = brightness(renderFilmLutCPU(im, options).data);
  for (const style of ["bw", "aero", "purple", "color"]) {
    const { data } = renderFilmLutCPU(im, { ...options, style });
    assert.ok(Math.abs(brightness(data) - expected) < 0.004);
    if (style === "bw") assert.ok(data[0] === data[1] && data[1] === data[2]);
  }
  const high = renderFilmLutCPU(im, {
    ...options,
    style: "bw",
    stripes: true,
    cameraEv: 10,
  }).data;
  const low = renderFilmLutCPU(im, {
    ...options,
    style: "bw",
    stripes: true,
    cameraEv: -10,
  }).data;
  assert.ok(high[0] > high[1] && low[1] > low[0]);
});
test("Fractional alpha and premultiplied scaling keep transparent edges clean", () => {
  const im = {
    width: 2,
    height: 1,
    data: new Uint8ClampedArray([255, 0, 0, 0, 70, 80, 90, 128]),
  };
  const full = renderFilmLutCPU(im, options);
  assert.deepEqual(Array.from(full.data.slice(0, 4)), [0, 0, 0, 0]);
  const scaled = renderFilmLutCPU(im, { ...options, scale: 2 });
  assert.deepEqual([scaled.width, scaled.height], [4, 2]);
  for (const i of [1, 2, 3])
    assert.deepEqual(
      scaled.data.slice(4 * i, 4 * i + 3),
      full.data.slice(4, 7),
    );
  assert.deepEqual(
    [0, 1, 2, 3].map((i) => scaled.data[4 * i + 3]),
    [0, 32, 96, 128],
  );
  assert.throws(() =>
    renderFilmLutCPU(im, { ...options, lut: new Uint8Array(3) }),
  );
});
test("Library resolves sibling files, deduplicates concurrent fetches and retries bad payloads", async () => {
  const original = globalThis.fetch;
  const urls = [];
  let bad = true;
  try {
    globalThis.fetch = async (url) => {
      urls.push(String(url));
      return String(url).endsWith("json")
        ? Response.json({ entries: [{ film: "demo", file: "a.bin" }] })
        : new Response(bad ? new Uint8Array(1) : lut);
    };
    const lib = new FilmLutLibrary("https://example.com/luts/index.json");
    const [a, b] = await Promise.all([lib.entries(), lib.entries()]);
    assert.equal(a, b);
    await assert.rejects(lib.load(a[0]));
    bad = false;
    const [c, d] = await Promise.all([lib.load(a[0]), lib.load(b[0])]);
    assert.equal(c, d);
    assert.deepEqual(c, lut);
    assert.deepEqual(urls, [
      "https://example.com/luts/index.json",
      ...Array(2).fill("https://example.com/luts/a.bin"),
    ]);
    for (let i = 0; i < 16; i++) await lib.load({ file: i + ".bin" });
    await lib.load(a[0]);
    assert.equal(urls.filter((url) => url.endsWith("/a.bin")).length, 3);
  } finally {
    globalThis.fetch = original;
  }
});

test(
  "WebGL2 3D texture interpolation matches the CPU transform",
  { skip: !process.env.LIGHTDATA_TEST_GL },
  async () => {
    const { chromium } = await import("@playwright/test");
    const { createServer } = await import("node:http");
    const { readFile } = await import("node:fs/promises");
    const server = createServer(async (req, res) => {
      if (req.url === "/") return res.end("<html></html>");
      if (!["/core.js", "/film-lut.js", "/online-reciprocity.js"].includes(req.url)) {
        res.writeHead(404);
        return res.end();
      }
      res.setHeader("Content-Type", "text/javascript");
      res.end(await readFile(new URL("." + req.url, import.meta.url)));
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    let browser;
    try {
      browser = await chromium.launch({
        executablePath:
          process.env.LIGHTDATA_CHROME ||
          (process.platform === "win32"
            ? "C:/Program Files/Google/Chrome/Application/chrome.exe"
            : undefined),
        args: ["--enable-unsafe-swiftshader", "--use-angle=swiftshader"],
      });
      const page = await browser.newPage();
      await page.goto("http://127.0.0.1:" + server.address().port);
      const result = await page.evaluate(
        async ({ film }) => {
          const { renderFilmLutCPU, createFilmLutRenderer } = await import(
            "./film-lut.js"
          );
          const cube = Uint8Array.from({ length: 33 ** 3 * 3 }, (_, i) => {
            const idx = Math.floor(i / 3),
              c = i % 3;
            return Math.round(
              255 *
                Math.min(
                  1,
                  ((Math.floor(idx / 33 ** ((c + 1) % 3)) % 33) / 32) * 0.85 +
                    0.04,
                ),
            );
          });
          const canvas = document.createElement("canvas"),
            renderer = createFilmLutRenderer();
          const image = {
            width: 39,
            height: 21,
            data: Uint8ClampedArray.from({ length: 39 * 21 * 4 }, (_, i) =>
              i % 4 === 3
                ? i % 11 === 0
                  ? 0
                  : 255
                : Math.floor((i * 1717) % 253),
            ),
          };
          const o = {
            film,
            lut: cube,
            preview: true,
            stripes: true,
            cameraEv: 0.7,
          };
          let max = 0,
            backend;
          for (const style of ["color", "purple", "aero", "bw"]) {
            const opts = { ...o, style };
            backend = renderer.draw(canvas, image, opts).backend;
            const raw = canvas.getContext("2d").getImageData(0, 0, 39, 21).data,
              ref = renderFilmLutCPU(image, opts).data;
            for (let i = 0; i < raw.length; i++)
              max = Math.max(max, Math.abs(raw[i] - ref[i]));
          }
          let nativeMax = 0;
          const layerFilm = {...film, color: {
            matrix: [[.8,.15,.05],[.1,.8,.1],[.2,.1,.7]],
            density: {red: film.dots, green: film.dots, blue: film.dots}
          }};
          for (const preview of [true, false]) for (const manual of [true, false]) {
            for (const style of ["color", "purple", "aero", "bw"]) {
              const opts = {...o, film: layerFilm, lut: null, preview, manual, style};
              if (renderer.draw(canvas, image, opts).backend !== "webgl2") throw Error("Missing native GPU path");
              const raw = canvas.getContext("2d").getImageData(0, 0, 39, 21).data;
              const ref = renderFilmLutCPU(image, opts).data;
              for (let i=0; i<raw.length; i++) nativeMax = Math.max(nativeMax, Math.abs(raw[i]-ref[i]));
            }
          }
          const source = document.createElement("canvas");
          source.width = image.width;
          source.height = image.height;
          const sourceContext = source.getContext("2d");
          sourceContext.putImageData(
            new ImageData(image.data, image.width, image.height),
            0,
            0,
          );
          const read = sourceContext.getImageData.bind(sourceContext);
          sourceContext.getImageData = () => {
            throw Error("GPU path read raw pixels to CPU");
          };
          const direct = renderer.draw(canvas, source, o);
          const actual = canvas
            .getContext("2d")
            .getImageData(0, 0, canvas.width, canvas.height).data;
          const reference = renderFilmLutCPU(image, o).data;
          const delta = (raw) =>
            raw.reduce((e, v, i) => Math.max(e, Math.abs(v - reference[i])), 0);
          max = Math.max(max, delta(actual));
          sourceContext.getImageData = read;
          const cpu = createFilmLutRenderer({ gpu: false });
          const fallback = cpu.draw(canvas, source, o);
          const cpuDifference = delta(
            canvas
              .getContext("2d")
              .getImageData(0, 0, canvas.width, canvas.height).data,
          );
          cpu.dispose();
          renderer.dispose();
          return {
            max,
            backend,
            direct: direct.backend,
            fallback: fallback.backend,
            cpuDifference, nativeMax,
          };
        },
        { film },
      );
      assert.equal(result.backend, "webgl2");
      assert.equal(result.direct, "webgl2");
      assert.equal(result.fallback, "cpu");
      assert.equal(result.cpuDifference, 0);
      assert.ok(result.nativeMax <= 2, "native GPU error " + result.nativeMax);
      assert.ok(result.max <= 3, "max error " + result.max);
    } finally {
      await browser?.close();
      server.closeAllConnections();
      await new Promise((resolve) => server.close(resolve));
    }
  },
);

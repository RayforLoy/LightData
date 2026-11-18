import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import { Camera, captureExposure, fasterScanExposure } from "./camera.js";

const originalDocument = globalThis.document;
before(() => {
  globalThis.document = { createElement: () => ({ getContext: () => ({}) }) };
});
after(() => {
  if (originalDocument === undefined) delete globalThis.document;
  else globalThis.document = originalDocument;
});

const capabilities = () => ({
  exposureMode: ["continuous", "manual"],
  exposureTime: { min: 1, max: 10000, step: .01 },
  iso: { min: 25, max: 3200, step: 1 },
  whiteBalanceMode: ["continuous", "manual"],
  colorTemperature: { min: 2000, max: 8000, step: 50 },
});
const settings = () => ({
  exposureMode: "continuous", exposureTime: 123.456, iso: 125,
  whiteBalanceMode: "continuous", colorTemperature: 5250,
  deviceId: "original-camera", width: 1280, height: 720,
});
const flatConstraints = ({ advanced = [], ...basic }) => Object.assign({}, basic, ...advanced);

function fakeCamera(options = {}) {
  const calls = [], observed = [];
  const track = {
    readyState: "live",
    actual: { ...settings(), ...options.settings },
    capabilities: { ...capabilities(), ...options.capabilities },
    constraints: {
      width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 30 },
      advanced: [{ exposureMode: "continuous", whiteBalanceMode: "continuous", zoom: 1.5 }],
    },
    getSettings() { return { ...this.actual }; },
    getCapabilities() { return structuredClone(this.capabilities); },
    getConstraints() { return structuredClone(this.constraints); },
    async applyConstraints(next) {
      const request = structuredClone(next);
      calls.push(request);
      if (options.beforeApply) await options.beforeApply(request, this);
      if (!options.ignore) {
        this.constraints = request;
        const flat = flatConstraints(request);
        // An omitted manual constraint may revert to the device's auto default.
        // This catches accidentally replacing AE while setting/restoring WB.
        this.actual.exposureMode = flat.exposureMode || "continuous";
        this.actual.whiteBalanceMode = flat.whiteBalanceMode || "continuous";
        for (const key of ["exposureTime", "iso", "colorTemperature"])
          if (Object.hasOwn(flat, key)) this.actual[key] = flat[key];
      }
      options.afterApply?.(request, this);
    },
    stop() { this.readyState = "ended"; },
  };
  const camera = new Camera({});
  camera.track = track;
  camera.stream = { getTracks: () => [track] };
  camera.capabilities = track.getCapabilities();
  camera.addEventListener("settings", ({ detail }) => observed.push({ ...detail }));
  return { camera, track, calls, observed };
}

test("locks both channels using actual API units, retains video constraints and restores once", async () => {
  const { camera, track, calls, observed } = fakeCamera();
  const lock = await camera.lockForPanorama();
  assert.equal(lock.exposureLocked, true);
  assert.equal(lock.whiteBalanceLocked, true);
  assert.equal(lock.exposureStatus, "locked");
  assert.equal(lock.whiteBalanceStatus, "locked");
  assert.equal(calls.length, 2);
  const request = flatConstraints(calls[1]);
  assert.equal(request.exposureTime, 123.456); // 100 microsecond units; not seconds.
  assert.equal(request.iso, 125);
  assert.equal(request.colorTemperature, 5250);
  assert.equal(request.exposureMode, "manual");
  assert.equal(request.whiteBalanceMode, "manual");
  assert.deepEqual(request.width, { ideal: 1280 });
  assert.deepEqual(request.frameRate, { ideal: 30 });
  assert.equal(request.zoom, 1.5);
  assert.ok(observed.some((value) => value.exposureMode === "manual"));
  assert.deepEqual(camera.settings, track.actual);
  assert.equal(captureExposure(camera.settings, { mode: "auto", aperture: 2 }).t, .0123456);
  const [first, second] = await Promise.all([lock.release(), lock.release()]);
  assert.equal(first, second);
  assert.deepEqual(first, { exposure: "restored", whiteBalance: "restored" });
  assert.equal(calls.length, 4);
  assert.equal(track.actual.exposureMode, "continuous");
  assert.equal(track.actual.whiteBalanceMode, "continuous");
  assert.equal(track.actual.exposureTime, 123.456);
  assert.equal(track.actual.iso, 125);
  assert.equal(track.actual.colorTemperature, 5250);
  assert.equal(track.readyState, "live");
});

test("scan shutter gets faster within ISO range and preserves the exposure product", () => {
  const limits = capabilities();
  assert.deepEqual(fasterScanExposure({ exposureTime: 400, iso: 100 }, limits), { exposureTime: 100, iso: 400 });
  assert.deepEqual(fasterScanExposure({ exposureTime: 300, iso: 1600 }, limits), { exposureTime: 150, iso: 3200 });
  assert.equal(fasterScanExposure({ exposureTime: 300, iso: 3200 }, limits), null);
  assert.equal(fasterScanExposure({ exposureTime: 105, iso: 100 }, limits), null);
  assert.equal(fasterScanExposure({ exposureTime: 300 }, limits), null);
});

test("a rejected faster shutter falls back to a verified original AE hold", async () => {
  const { camera, track, calls } = fakeCamera({
    settings: { exposureTime: 400, iso: 200 },
    beforeApply(request) {
      if (flatConstraints(request).exposureTime === 100) throw new Error("Device does not support this combination");
    },
  });
  const lock = await camera.lockForPanorama({ preferFaster: true });
  assert.equal(flatConstraints(calls[0]).iso, 800);
  assert.equal(flatConstraints(calls[1]).exposureTime, 400);
  assert.equal(lock.exposureLocked, true);
  assert.equal(track.actual.exposureTime, 400);
  await lock.release();
  assert.equal(track.actual.exposureMode, "continuous");
});

test("reports quantized actual values instead of fabricating requested metadata", async () => {
  const { camera, track, calls } = fakeCamera({
    afterApply(request, track) {
      if (flatConstraints(request).exposureMode === "manual") {
        track.actual.exposureTime = 125;
        track.actual.iso = 128;
      }
    },
  });
  const lock = await camera.lockForPanorama();
  assert.equal(lock.exposureLocked, true);
  assert.equal(camera.settings.exposureTime, 125);
  assert.equal(camera.settings.iso, 128);
  assert.equal(track.actual.exposureMode, "manual");
  assert.equal(flatConstraints(calls[0]).exposureTime, 123.456);
  assert.deepEqual(captureExposure(camera.settings, { mode: "auto", aperture: 2 }), {
    a: 2, t: .0125, iso: 128, source: "固定参数 · 预览估测",
  });
  assert.equal((await lock.release()).exposure, "restored");
});

test("no track or unavailable capabilities return a harmless handle", async () => {
  const { camera, track, calls } = fakeCamera();
  track.getCapabilities = () => { throw new Error("capabilities unavailable"); };
  const unavailable = await camera.lockForPanorama();
  assert.equal(unavailable.exposureLocked, false);
  assert.equal(unavailable.whiteBalanceLocked, false);
  assert.match(unavailable.errors.capabilities, /unavailable/);
  await unavailable.release();
  camera.track = null;
  camera.stream = null;
  const absent = await camera.lockForPanorama();
  assert.equal(absent.exposureLocked, false);
  assert.equal(absent.whiteBalanceLocked, false);
  assert.deepEqual(await absent.release(), { exposure: "unchanged", whiteBalance: "unchanged" });
  assert.equal(calls.length, 0);
});

test("manual mode alone or stale cached capabilities do not justify a lock", async () => {
  const { camera, track, calls } = fakeCamera({ capabilities: {
    iso: undefined, colorTemperature: undefined,
  } });
  camera.capabilities = capabilities(); // A fresh track query must take precedence.
  const lock = await camera.lockForPanorama();
  assert.equal(lock.exposureStatus, "unsupported");
  assert.equal(lock.whiteBalanceStatus, "unsupported");
  assert.equal(lock.exposureLocked, false);
  assert.equal(lock.whiteBalanceLocked, false);
  assert.equal(calls.length, 0);
  assert.equal(track.readyState, "live");
  await lock.release();
  delete track.getCapabilities;
  const noCapabilityAPI = await camera.lockForPanorama();
  assert.equal(noCapabilityAPI.exposureLocked, false);
  assert.equal(noCapabilityAPI.whiteBalanceLocked, false);
  assert.equal(calls.length, 0);
  await noCapabilityAPI.release();
});

test("missing, nonfinite, out-of-range or un-restorable exposure settings never get pinned", async () => {
  for (const bad of [
    { exposureTime: undefined }, { exposureTime: 0 }, { exposureTime: Infinity },
    { exposureTime: "100" }, { exposureTime: 20000 }, { iso: NaN }, { iso: 0 },
    { iso: undefined }, { exposureMode: undefined }, { exposureMode: "unknown" },
  ]) {
    const { camera, calls } = fakeCamera({ settings: bad,
      capabilities: { whiteBalanceMode: ["continuous"] } });
    const lock = await camera.lockForPanorama();
    assert.equal(lock.exposureLocked, false, JSON.stringify(bad));
    assert.equal(lock.exposureStatus, "incomplete-settings");
    assert.equal(calls.length, 0);
    await lock.release();
  }
});

test("invalid white balance can be skipped independently of AE", async () => {
  for (const colorTemperature of [undefined, 0, Infinity, "5000", 9000]) {
    const { camera, calls } = fakeCamera({ settings: { colorTemperature } });
    const lock = await camera.lockForPanorama();
    assert.equal(lock.exposureLocked, true);
    assert.equal(lock.whiteBalanceLocked, false);
    assert.equal(lock.whiteBalanceStatus, "incomplete-settings");
    assert.equal(calls.length, 1);
    assert.equal((await lock.release()).exposure, "restored");
  }
});

test("AE and AWB constraint rejections remain independent and never stop the stream", async () => {
  for (const reject of ["exposureMode", "whiteBalanceMode"]) {
    const { camera, track, calls } = fakeCamera({
      beforeApply(request, track) {
        const last = request.advanced.at(-1);
        if (last[reject] === "manual" && track.actual[reject] !== "manual")
          throw new Error("not permitted");
      },
    });
    const lock = await camera.lockForPanorama();
    const failed = reject === "exposureMode" ? "exposure" : "whiteBalance";
    const other = failed === "exposure" ? "whiteBalance" : "exposure";
    assert.equal(lock[failed + "Locked"], false);
    assert.equal(lock[failed + "Status"], "rejected");
    assert.equal(lock[other + "Locked"], true);
    const restored = await lock.release();
    assert.equal(restored[failed], "unchanged");
    assert.equal(restored[other], "restored");
    assert.equal(calls.length, 3);
    assert.equal(track.readyState, "live");
  }
});

test("fulfilled but ignored constraints are not reported as successful locks", async () => {
  const { camera, calls } = fakeCamera({ ignore: true });
  const lock = await camera.lockForPanorama();
  assert.equal(lock.exposureLocked, false);
  assert.equal(lock.whiteBalanceLocked, false);
  assert.equal(lock.exposureStatus, "unconfirmed");
  assert.equal(lock.whiteBalanceStatus, "unconfirmed");
  assert.deepEqual(await lock.release(), { exposure: "unchanged", whiteBalance: "unchanged" });
  assert.equal(calls.length, 2);
  assert.equal(camera.settings.exposureMode, "continuous");
});

test("partially reported manual exposure remains unconfirmed but can be released", async () => {
  const { camera, track } = fakeCamera({
    afterApply(request, track) {
      if (flatConstraints(request).exposureMode === "manual") delete track.actual.iso;
    },
  });
  const lock = await camera.lockForPanorama();
  assert.equal(lock.exposureLocked, false);
  assert.equal(lock.whiteBalanceLocked, true);
  assert.equal(lock.exposureStatus, "unconfirmed");
  assert.equal(captureExposure(camera.settings, { mode: "auto", aperture: 2 }), null);
  assert.equal((await lock.release()).exposure, "restored");
  assert.equal(track.actual.iso, 125);
});

test("pre-existing manual controls remain untouched, including on release", async () => {
  const { camera, track, calls } = fakeCamera({ settings: {
    exposureMode: "manual", whiteBalanceMode: "manual",
  } });
  const original = { ...track.actual };
  const lock = await camera.lockForPanorama();
  assert.equal(lock.exposureLocked, true);
  assert.equal(lock.whiteBalanceLocked, true);
  assert.equal(lock.exposureStatus, "already-manual");
  assert.equal(lock.whiteBalanceStatus, "already-manual");
  assert.deepEqual(await lock.release(), { exposure: "unchanged", whiteBalance: "unchanged" });
  assert.equal(calls.length, 0);
  assert.deepEqual(track.actual, original);
});

test("locking or restoring one channel preserves an existing manual other channel even without retained constraints", async () => {
  for (const manual of ["exposureMode", "whiteBalanceMode"]) {
    const { camera, track } = fakeCamera({ settings: { [manual]: "manual" } });
    // This fixture deliberately exposes manual settings with old auto constraints.
    const lock = await camera.lockForPanorama();
    assert.equal(lock.exposureLocked, true);
    assert.equal(lock.whiteBalanceLocked, true);
    assert.equal(track.actual[manual], "manual");
    const restored = await lock.release();
    assert.equal(restored[manual === "exposureMode" ? "exposure" : "whiteBalance"], "unchanged");
    assert.equal(track.actual[manual], "manual");
    assert.equal(track.actual.exposureTime, 123.456);
    assert.equal(track.actual.iso, 125);
    assert.equal(track.actual.colorTemperature, 5250);
  }
});

test("release preserves a user's changed AE while restoring only WB", async () => {
  const { camera, track, calls } = fakeCamera();
  const lock = await camera.lockForPanorama();
  const user = track.getConstraints();
  user.advanced.push({ exposureMode: "manual", exposureTime: 500, iso: 400 });
  await track.applyConstraints(user);
  const beforeRelease = calls.length;
  assert.deepEqual(await lock.release(), { exposure: "skipped-changed", whiteBalance: "restored" });
  assert.equal(calls.length, beforeRelease + 1);
  assert.equal(track.actual.exposureMode, "manual");
  assert.equal(track.actual.exposureTime, 500);
  assert.equal(track.actual.iso, 400);
  assert.equal(track.actual.whiteBalanceMode, "continuous");
});

test("release preserves a user's changed WB while restoring only AE", async () => {
  const { camera, track } = fakeCamera();
  const lock = await camera.lockForPanorama();
  const user = track.getConstraints();
  user.advanced.push({ whiteBalanceMode: "manual", colorTemperature: 6500 });
  await track.applyConstraints(user);
  assert.deepEqual(await lock.release(), { exposure: "restored", whiteBalance: "skipped-changed" });
  assert.equal(track.actual.exposureMode, "continuous");
  assert.equal(track.actual.whiteBalanceMode, "manual");
  assert.equal(track.actual.colorTemperature, 6500);
});

test("an observed user change remains protected if the values later return", async () => {
  const { camera, track } = fakeCamera();
  const lock = await camera.lockForPanorama();
  track.actual.iso = 250;
  camera.readSettings();
  track.actual.iso = 125;
  camera.readSettings();
  assert.equal(lock.exposureLocked, false);
  assert.equal(lock.exposureStatus, "changed");
  assert.equal((await lock.release()).exposure, "skipped-changed");
  assert.equal(track.actual.exposureMode, "manual");
});

test("a later user fixedExposure command owns AE even when it selects identical values", async () => {
  const { camera, track } = fakeCamera({ capabilities: { whiteBalanceMode: ["continuous"] } });
  const lock = await camera.lockForPanorama();
  await camera.fixedExposure(123.456 / 10000, 125);
  assert.equal((await lock.release()).exposure, "skipped-changed");
  assert.equal(track.actual.exposureMode, "manual");
});

test("release cannot change a replacement camera or a new session on the same track", async () => {
  for (const replace of [false, true]) {
    const { camera, calls } = fakeCamera();
    const lock = await camera.lockForPanorama();
    const replacement = fakeCamera();
    if (replace) {
      camera.track = replacement.track;
      camera.stream = replacement.camera.stream;
      camera.settings = { deviceId: "new-camera" };
    }
    camera.generation++;
    assert.deepEqual(await lock.release(), { exposure: "skipped-session", whiteBalance: "skipped-session" });
    assert.equal(calls.length, 2);
    assert.equal(replacement.calls.length, 0);
    if (replace) assert.deepEqual(camera.settings, { deviceId: "new-camera" });
  }
});

test("switching cameras during a pending lock cannot publish stale metadata or lock the new camera", async () => {
  let resume;
  const pendingConstraint = new Promise((resolve) => { resume = resolve; });
  const { camera, calls } = fakeCamera({ beforeApply: () => pendingConstraint });
  const waiting = camera.lockForPanorama();
  const next = fakeCamera();
  camera.track = next.track;
  camera.stream = next.camera.stream;
  camera.generation++;
  camera.settings = { deviceId: "replacement" };
  resume();
  const lock = await waiting;
  assert.equal(lock.exposureLocked, false);
  assert.equal(lock.whiteBalanceLocked, false);
  assert.equal(lock.exposureStatus, "superseded");
  assert.equal(calls.length, 1);
  assert.equal(next.calls.length, 0);
  assert.deepEqual(camera.settings, { deviceId: "replacement" });
  await lock.release();
  assert.equal(next.calls.length, 0);
});

test("concurrent callers share one hold and concurrent releases restore only once", async () => {
  const { camera, calls } = fakeCamera();
  const [first, second] = await Promise.all([camera.lockForPanorama(), camera.lockForPanorama()]);
  assert.equal(first, second);
  assert.equal(calls.length, 2);
  const [a, b] = await Promise.all([first.release(), second.release()]);
  assert.equal(a, b);
  assert.equal(calls.length, 4);
  const fresh = await camera.lockForPanorama();
  assert.notEqual(fresh, first);
  assert.equal(calls.length, 6);
  await fresh.release();
});

test("a new hold waits for an in-flight release before acquiring manual controls again", async () => {
  let restoring = false, resume;
  const gate = new Promise((resolve) => { resume = resolve; });
  const { camera, track, calls } = fakeCamera({
    beforeApply(request) {
      if (restoring && request.advanced.at(-1).exposureMode === "continuous") return gate;
    },
  });
  const old = await camera.lockForPanorama();
  restoring = true;
  const releasing = old.release();
  const next = camera.lockForPanorama();
  assert.equal(calls.length, 3); // No new lock can race the pending restore.
  resume();
  assert.deepEqual(await releasing, { exposure: "restored", whiteBalance: "restored" });
  const fresh = await next;
  assert.notEqual(fresh, old);
  assert.equal(fresh.exposureLocked, true);
  assert.equal(fresh.whiteBalanceLocked, true);
  assert.equal(track.actual.exposureMode, "manual");
  assert.equal(track.actual.whiteBalanceMode, "manual");
  assert.equal(calls.length, 6);
  restoring = false;
  await fresh.release();
});

test("one restore failure does not prevent the other channel or interrupt video", async () => {
  let restoring = false;
  const { camera, track } = fakeCamera({ beforeApply(request) {
    if (restoring && request.advanced.at(-1).exposureMode === "continuous")
      throw new Error("restore denied");
  } });
  const lock = await camera.lockForPanorama();
  restoring = true;
  assert.deepEqual(await lock.release(), { exposure: "failed", whiteBalance: "restored" });
  assert.match(lock.errors.exposureRelease, /restore denied/);
  assert.equal(track.readyState, "live");
  assert.equal(track.actual.exposureMode, "manual");
  assert.equal(track.actual.whiteBalanceMode, "continuous");
});

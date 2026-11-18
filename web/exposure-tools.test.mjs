import test from "node:test";
import assert from "node:assert/strict";
import { defaultResponse, equivalentExposures, exposureEV } from "./core.js";
import { dimMeterWarning, groundCenter, groundReading, GroundBurst, measuredTransmissionLoss,
  opticalLossStops, shootingExposure, zoneMiddleEv } from "./exposure-tools.js";
import { finderLevel } from "./finder-level.js";

const near = (value, expected, tolerance = 1e-10) => assert.ok(Math.abs(value - expected) < tolerance,
  `Expected ${value} within ${tolerance} of ${expected}`);
const manual = { a: 8, t: 1 / 125, iso: 100 };

test("light loss extends the actual exposure while preserving the effective middle-gray EV", () => {
  const loss = opticalLossStops(1, 300, 150);
  assert.equal(loss, 3);
  assert.equal(opticalLossStops(1, 150, 150), 1);
  assert.equal(opticalLossStops(1, 0, 150), 1);
  const scene = exposureEV(manual);
  for (const mode of ["A", "S"]) {
    const result = shootingExposure(mode, manual, scene, loss, 1, false);
    near(exposureEV(result) + loss, scene - 1, .18);
    assert.equal(mode === "A" ? result.a : result.t, mode === "A" ? manual.a : manual.t);
  }
  assert.deepEqual(shootingExposure("M", manual, scene, loss, 0, false), manual);
});

test("placement uses absolute EV, bypasses the ambient bias, and retains M mode aperture", () => {
  assert.equal(zoneMiddleEv(12, 5), 12);
  assert.equal(zoneMiddleEv(12, 8), 9);
  const exposure = shootingExposure("M", manual, zoneMiddleEv(12, 8), 2, 3, true);
  assert.equal(exposure.a, 8);
  assert.equal(exposure.iso, 100);
  near(exposureEV(exposure) + 2, 9, .12);
  const long = shootingExposure("A", manual, 0, 3, 0, false);
  assert.equal(long.t, 512);
  assert.ok(equivalentExposures(long, { exactLong: true }).length > 3);
});

test("ground center rejects unavailable, clipped and unobserved pixels", () => {
  const image = gray(128);
  near(groundCenter(image, 5, defaultResponse), 5 + defaultResponse[128]);
  assert.equal(groundCenter(gray(0), 5, defaultResponse), null);
  assert.equal(groundCenter(gray(255), 5, defaultResponse), null);
  assert.equal(groundCenter(image, null, defaultResponse), null);
  image.data[3] = 0;
  assert.equal(groundCenter(image, 5, defaultResponse), null);
});

function gray(code) {
  const data = new Uint8ClampedArray(5 * 5 * 4).fill(code);
  for (let i = 3; i < data.length; i += 4) data[i] = 255;
  return { width: 5, height: 5, data };
}

test("ground comparison waits for distinct stable frames and folds in working aperture once", () => {
  const burst = new GroundBurst(5);
  assert.equal(burst.observe(4, 3), null);
  assert.equal(burst.observe(6, 3), null);
  assert.equal(burst.observe(6, 0), null);
  assert.equal(burst.observe(7, null), null);
  for (let time = 8; time < 12; time++) assert.equal(burst.observe(time, 4), null);
  assert.equal(burst.observe(12, 3), null);
  for (let time = 13; time < 16; time++) assert.equal(burst.observe(time, 3), null);
  assert.equal(burst.observe(16, 3), 3);
  const estimate = groundReading(3, { offset: 2, referenceAperture: 8 }, 16, 200);
  assert.deepEqual(estimate, { equivalentEv: 7, seconds: 1 });
  assert.equal(measuredTransmissionLoss(10, estimate.equivalentEv), 3);
  assert.equal(measuredTransmissionLoss(5, estimate.equivalentEv), null);
});

test("low-light hint requires an absolute measured scene and valid histogram", () => {
  const histogram = new Uint32Array(256);
  assert.equal(dimMeterWarning(-1, histogram), false);
  histogram[16] = 100;
  assert.equal(dimMeterWarning(null, histogram), false);
  assert.equal(dimMeterWarning(2, histogram), true);
  assert.equal(dimMeterWarning(3.01, histogram), false);
  histogram[60] = 100;
  assert.equal(dimMeterWarning(2, histogram), false);
  assert.equal(dimMeterWarning(0, histogram), true);
});

test("level follows tilt and display direction with a gravity-only overhead bubble", () => {
  const portrait = finderLevel(90, 0, 0);
  assert.equal(portrait.level, true);
  assert.equal(portrait.overhead, false);
  near(portrait.roll, 0);
  near(finderLevel(90, 0, 90).roll, -90);
  const overhead = finderLevel(0, 0, 0);
  assert.equal(overhead.overhead, true);
  assert.equal(overhead.level, true);
  assert.equal(finderLevel(0, 20, 0).level, false);
  assert.equal(finderLevel(null, 0, 0), null);
});

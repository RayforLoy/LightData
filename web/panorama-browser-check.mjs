import { chromium, expect } from "@playwright/test";
import http from "node:http";
import fs from "node:fs/promises";
import path from "node:path";
import os from "node:os";
import { fileURLToPath } from "node:url";

// Exercise the real app, video pipeline and panorama implementation. The only
// doubles are a repeatable camera scene / capture metadata and orientation events.
const root = path.dirname(fileURLToPath(import.meta.url));
const mime = { ".html": "text/html", ".js": "application/javascript",
  ".css": "text/css", ".json": "application/json", ".png": "image/png" };
const server = http.createServer(async (request, response) => {
  try {
    const pathname = decodeURIComponent(new URL(request.url, "http://localhost").pathname);
    const file = path.resolve(root, "." + (pathname === "/" ? "/index.html" : pathname));
    if (!file.startsWith(root + path.sep)) throw new Error("outside static root");
    response.setHeader("Content-Type", mime[path.extname(file)] || "application/octet-stream");
    response.setHeader("Cache-Control", "no-store");
    response.end(await fs.readFile(file));
  } catch {
    response.writeHead(404);
    response.end();
  }
});
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const base = process.env.LIGHTDATA_URL || `http://127.0.0.1:${server.address().port}`;
const fixtureDirectory = await fs.mkdtemp(path.join(os.tmpdir(), "lightdata-panorama-"));
const fixtureFile = path.join(fixtureDirectory, "scene.y4m");
const width = 1280, height = 720, area = width * height;
const yuv = Buffer.alloc(area * 3 / 2, 128);
for (let y = 0; y < height; y++)
  for (let x = 0; x < width; x++)
    yuv[y * width + x] = 42 + Math.floor(145 * x / width) + (((x >> 5) + (y >> 5)) % 2) * 24;
await fs.writeFile(fixtureFile, Buffer.concat([
  Buffer.from(`YUV4MPEG2 W${width} H${height} F20:1 Ip A1:1 C420jpeg\nFRAME\n`), yuv,
]));

const errors = [];
const snapshots = process.env.LIGHTDATA_PANORAMA_SHOTS || path.join(os.tmpdir(), "LightDataPanoramaQA");
await fs.mkdir(snapshots, { recursive: true });
let browser;
const check = expect.configure({ timeout: 10000 });

async function openCamera({ sensor = true, language = "zh-CN" } = {}) {
  const context = await browser.newContext({
    locale: language, viewport: { width: 430, height: 932 },
    permissions: ["camera"], deviceScaleFactor: 1,
  });
  const page = await context.newPage();
  page.on("pageerror", (error) => errors.push(error.message));
  await page.addInitScript(({ sensor, language }) => {
    localStorage.setItem("lightdata.language", language);
    localStorage.setItem("lightdata.web.v1", JSON.stringify({ warned: true }));
    globalThis.testScreenAngle = 0;
    Object.defineProperty(screen.orientation, "angle", {
      configurable: true, get: () => globalThis.testScreenAngle,
    });
    const original = MediaStreamTrack.prototype.getSettings;
    MediaStreamTrack.prototype.getSettings = function () {
      return { ...original.call(this), deviceId: "lightdata-panorama-camera",
        exposureMode: "continuous", exposureTime: globalThis.testExposureTime ?? 100, iso: 100 };
    };
    if (!sensor) delete window.DeviceOrientationEvent;
    else if (typeof DeviceOrientationEvent.requestPermission === "function")
      DeviceOrientationEvent.requestPermission = async () => "granted";
  }, { sensor, language });
  await page.goto(base);
  await check(page.locator("#camera-setup")).toBeVisible();
  await page.locator("#setup-aperture").fill("2.3");
  await page.locator("#setup-hfov").fill("70");
  await page.locator("#setup-save").click();
  await check(page.locator("#camera-setup")).toBeHidden();
  await check(page.locator("#live-state")).toContainText("LIVE");
  await check(page.locator("#meter-reading")).toContainText("EV100");
  await check(page.locator("#chart")).toHaveAttribute("aria-label", /EV100/);
  await page.locator("#focal").fill("8");
  await page.locator("#focal").dispatchEvent("change");
  await check(page.locator("#scan-start")).toBeVisible();
  await instrument(page);
  if (sensor) await startOrientation(page);
  return { context, page };
}

async function instrument(page) {
  await page.evaluate(async () => {
    const { Panorama } = await import("./panorama.js");
    const { Camera } = await import("./camera.js");
    const qa = globalThis.panoramaQA = {
      capture: 0, write: 0, accepted: 0, register: 0, view: 0,
      frameDecode: 0, trackingDecode: 0, analysis: 0, previewRead: 0,
      poseCaptures: 0, fallbackCaptures: 0, collecting: 0,
      analysisEdges: [],
    };
    qa.processing = [];
    for (const method of ["capture", "writeFrame", "register", "view", "applySnapshot"]) {
      const original = Panorama.prototype[method];
      Panorama.prototype[method] = function (...args) {
        if (method === "capture") {
          qa.capture++;
          const options = { ...args[0] };
          qa[options.pose ? "poseCaptures" : "fallbackCaptures"]++;
          if (options.collect !== false) qa.collecting++;
          for (const [key, count] of [["frame", "frameDecode"], ["trackingFrame", "trackingDecode"]]) {
            if (typeof options[key] === "function") {
              const decode = options[key];
              options[key] = (...parameters) => { qa[count]++; return decode(...parameters); };
            }
          }
          args[0] = options;
        } else if (method === "applySnapshot") {
          qa.write++;
          qa.accepted = args[0].captures;
          if (args[0].registration) qa.register++;
          qa.processing.push(args[0].processingMs);
        } else if (method === "writeFrame") qa.write++;
        else qa[method]++;
        const result = original.apply(this, args);
        if (method === "writeFrame" && result) qa.accepted++;
        if (method === "capture")
          qa.lastCapture = { accepted: result.accepted, settling: result.settling,
            captures: result.captures, coverage: result.coverage, collect: args[0].collect !== false,
            orientation: [...this.orientation] };
        if (method === "view")
          qa.lastView = { coverage: result.coverage, missing: result.missing,
            width: result.image.width, height: result.image.height };
        return result;
      };
    }
    for (const [method, count] of [["captureAnalysis", "analysis"], ["capturePreview", "previewRead"]]) {
      const original = Camera.prototype[method];
      Camera.prototype[method] = function (...args) {
        qa[count]++;
        if (method === "captureAnalysis") qa.analysisEdges.push(args[0] ?? 640);
        return original.apply(this, args);
      };
    }
  });
}

async function startOrientation(page) {
  await page.evaluate(() => {
    if (typeof DeviceOrientationEvent !== "function") throw new Error("Orientation event fixture unavailable");
    globalThis.testOrientation = { alpha: 0, beta: 90, gamma: 0, step: 0 };
    globalThis.testOrientationTimer = setInterval(() => {
      const pose = globalThis.testOrientation;
      pose.alpha = (pose.alpha + pose.step + 360) % 360;
      window.dispatchEvent(new DeviceOrientationEvent("deviceorientation", {
        alpha: pose.alpha, beta: pose.beta, gamma: pose.gamma, absolute: true,
      }));
    }, 30);
  });
}

async function orient(page, alpha, step = 0) {
  await page.evaluate(({ alpha, step }) => {
    globalThis.testOrientation.alpha = alpha;
    globalThis.testOrientation.step = step;
  }, { alpha, step });
}

async function rotateScreen(page, angle) {
  await page.evaluate((angle) => {
    globalThis.testScreenAngle = angle;
    screen.orientation.dispatchEvent(new Event("change"));
  }, angle);
  await page.setViewportSize(angle % 180
    ? { width: 932, height: 430 } : { width: 430, height: 932 });
}

const counts = (page) => page.evaluate(() => ({ ...globalThis.panoramaQA }));
async function pixels(page) {
  return page.locator("#view").evaluate((canvas) => {
    const { width, height } = canvas;
    const data = canvas.getContext("2d").getImageData(0, 0, width, height).data;
    let empty = 0, lit = 0, invalidEmpty = 0, hash = 0;
    let validPoint = null, missingPoint = null;
    for (let p = 0; p < width * height; p++) {
      const i = p * 4;
      hash = (Math.imul(hash, 31) + data[i] + 3 * data[i + 1] +
        7 * data[i + 2] + 13 * data[i + 3]) >>> 0;
      if (!data[i + 3]) {
        empty++;
        if (data[i] || data[i + 1] || data[i + 2]) invalidEmpty++;
      } else if (data[i] || data[i + 1] || data[i + 2]) lit++;
      // Stay clear of the transparent/valid boundary and UI overlays for clicks.
      const x = p % width, y = Math.floor(p / width);
      if (x > width * .15 && x < width * .85 && y > height * .15 && y < height * .85) {
        if (!data[i + 3] && !missingPoint) missingPoint = { x: x / width, y: y / height };
        if (data[i + 3] && !validPoint) validPoint = { x: x / width, y: y / height };
      }
    }
    return { width, height, hash, empty: empty / (width * height), lit: lit / (width * height),
      invalidEmpty, validPoint, missingPoint };
  });
}

async function clickView(page, point) {
  const box = await page.locator("#view").boundingBox();
  await page.locator("#view").click({ position: { x: box.width * point.x, y: box.height * point.y } });
}

async function dismissNotice(page) {
  if (await page.locator("#notice").isVisible()) await page.locator("#notice-ok").click();
}

async function poseScan() {
  const { context, page } = await openCamera();
  try {
    await page.locator("#scan-start").click();
    await check(page.locator("#scan-panel")).toBeVisible();
    await check.poll(async () => (await counts(page)).accepted).toBe(1);
    await check(page.locator("#scan-level")).toContainText("起点");
    await check(page.locator("#coverage-map")).toHaveAttribute("aria-label", /十字为起点，白圈为当前方向/);
    await page.evaluate(() => { globalThis.testExposureTime = 400; });
    await check(page.locator("#scan-low-light")).toBeVisible();
    await page.evaluate(() => { delete globalThis.testExposureTime; });
    await check(page.locator("#scan-low-light")).toBeHidden();
    await check.poll(async () => (await pixels(page)).lit).toBeGreaterThan(.02);
    const center = await pixels(page);
    expect(center.lit).toBeGreaterThan(.02);
    expect(center.empty).toBeGreaterThan(.1);
    expect(center.invalidEmpty).toBe(0);
    await page.screenshot({ path: path.join(snapshots, "collecting.png") });
    await page.waitForTimeout(700);
    const settled = await counts(page);
    expect(settled.accepted).toBe(1);
    expect(settled.write).toBe(1);

    // Keep changing pose faster than the settling window. Sensor-only motion
    // must not decode camera pixels or register / write any new atlas frames.
    await orient(page, 0, 2);
    await page.waitForTimeout(1000);
    const moving = await counts(page);
    expect(moving.poseCaptures).toBeGreaterThan(settled.poseCaptures + 5);
    for (const key of ["write", "register", "frameDecode", "trackingDecode", "analysis", "previewRead"])
      expect(moving[key], `continuous motion changed ${key}`).toBe(settled[key]);

    // Return to an overlapping new direction and stop. One keyframe, then none
    // at the same direction even while the video and sensor events keep coming.
    await orient(page, 20);
    await check.poll(async () => (await counts(page)).accepted).toBe(2);
    const stopped = await counts(page);
    await page.waitForTimeout(900);
    const held = await counts(page);
    expect(held.accepted).toBe(2);
    expect(held.write).toBe(stopped.write);
    expect(held.frameDecode).toBe(stopped.frameDecode);

    await page.locator("#scan-finish").click();
    await check(page.locator("#scan-status")).toContainText("二次构图");
    const finished = await counts(page);
    await orient(page, 0);
    await check.poll(async () => (await pixels(page)).lit).toBeGreaterThan(.02);
    const atCenter = await pixels(page);

    await orient(page, 80);
    await check.poll(async () => (await pixels(page)).empty).toBeGreaterThan(atCenter.empty + .03);
    await check(page.locator("#scan-home")).toBeVisible();
    await check(page.locator("#scan-home span")).toContainText("回到拼图中心");
    const right = await page.locator("#scan-home").evaluate((element) => ({
      x: Number(element.dataset.x), y: Number(element.dataset.y),
    }));
    expect(Math.hypot(right.x, right.y)).toBeCloseTo(1, 2);
    expect(Math.abs(right.x)).toBeGreaterThan(.9);

    // Keep the same raw sensor pose while rotating the screen's coordinate
    // system. The fixed Y4M raster is not a claim about real device video quality.
    await page.waitForTimeout(450); // Let motion preview return to native resolution.
    const unturned = await pixels(page);
    for (const [angle, expected] of [
      [90, { x: right.y, y: -right.x }],
      [180, { x: -right.x, y: -right.y }],
      [270, { x: -right.y, y: right.x }],
    ]) {
      await rotateScreen(page, angle);
      await check(page.locator("#scan-panel")).toBeVisible();
      for (const axis of ["x", "y"])
        await check.poll(async () => Number(await page.locator("#scan-home").getAttribute(`data-${axis}`)))
          .toBeCloseTo(expected[axis], 2);
      await check.poll(async () => {
        const view = await pixels(page);
        return [view.width, view.height];
      }).toEqual(angle % 180
        ? [unturned.height, unturned.width] : [unturned.width, unturned.height]);
    }
    await rotateScreen(page, 0);
    await check.poll(async () => Number(await page.locator("#scan-home").getAttribute("data-x")))
      .toBeCloseTo(right.x, 2);

    await orient(page, 280);
    await check.poll(async () => right.x * Number(await page.locator("#scan-home").getAttribute("data-x")))
      .toBeLessThan(-.8);
    const left = await page.locator("#scan-home").evaluate((element) => ({
      x: Number(element.dataset.x), y: Number(element.dataset.y),
    }));
    expect(right.x * left.x).toBeLessThan(-.8);

    await orient(page, 180);
    await check.poll(async () => (await pixels(page)).empty).toBe(1);
    const behind = await pixels(page);
    expect(behind.lit).toBe(0);
    expect(behind.invalidEmpty).toBe(0);
    await page.screenshot({ path: path.join(snapshots, "behind.png") });
    await check.poll(async () => /^[-+]?\d+(?:\.\d+)?\s+EV100/.test(
      (await page.locator("#meter-reading").textContent()).trim())).toBe(false);
    await check(page.locator("#meter-reading")).toContainText("无有效测光");
    await check(page.locator("#chart")).toHaveAttribute("aria-label", /直方图横轴/);

    // An empty crop is unavailable data, including during frozen spot metering.
    await page.locator("#spots-toggle").click();
    await dismissNotice(page);
    await clickView(page, { x: .5, y: .5 });
    await check(page.locator("#spots .spot")).toHaveCount(0);
    await check(page.locator("#toast")).toContainText(/未采集|补拍/);
    await page.locator("#spots-toggle").click();

    // Viewport rotation must keep the same atlas / collection mode. The media
    // fixture stays landscape; real video-rotation geometry is covered in the
    // panorama unit tests rather than faked by accessing private app state.
    await orient(page, 0);
    await page.setViewportSize({ width: 932, height: 430 });
    await check(page.locator("#scan-panel")).toBeVisible();
    await check(page.locator("#scan-status")).toContainText("二次构图");
    await check.poll(async () => (await pixels(page)).lit).toBeGreaterThan(.02);
    await page.setViewportSize({ width: 430, height: 932 });
    await check(page.locator("#scan-panel")).toBeVisible();
    await orient(page, 30);
    await page.waitForTimeout(500);
    const afterFinish = await counts(page);
    expect(afterFinish.view).toBeGreaterThan(finished.view);
    expect(afterFinish.write).toBe(finished.write);
    // Freeze intentionally takes one display snapshot; no new atlas frame.
    expect(afterFinish.frameDecode).toBe(finished.frameDecode);
    expect(afterFinish.register).toBe(finished.register);

    await page.locator("#scan-cancel").click();
    await check(page.locator("#scan-panel")).toBeHidden();
    const exited = await counts(page);
    await check.poll(async () => (await counts(page)).analysis).toBeGreaterThan(exited.analysis);
    await check(page.locator("#meter-reading")).toContainText("EV100");
    console.log("PASS sensor panorama: excessive motion rejected, stationary recovery, black rays, home arrows, spots, rotation, exit");
  } finally {
    await context.close();
  }
}

async function continuousScan() {
  const { context, page } = await openCamera();
  try {
    // The actual media track still supplies preview events. For this case only,
    // analysis pixels are a deterministic rectilinear scene tied to the injected
    // pose; a fixed Y4M image cannot represent turning a camera around a scene.
    await page.evaluate(async () => {
      const { Camera } = await import("./camera.js");
      const original = Camera.prototype.captureAnalysis;
      Camera.prototype.captureAnalysis = function (maxEdge = 640) {
        const base = original.call(this, maxEdge);
        if (!base || !globalThis.testOrientation) return base;
        const yaw = globalThis.testOrientation.alpha * Math.PI / 180;
        const sx = Math.tan(35 * Math.PI / 180), sy = sx * base.height / base.width;
        const c = Math.cos(yaw), s = Math.sin(yaw), data = base.data;
        for (let y = 0; y < base.height; y++) for (let x = 0; x < base.width; x++) {
          const u = (2 * (x + .5) / base.width - 1) * sx;
          const v = (1 - 2 * (y + .5) / base.height) * sy;
          const z = c + s * u, xx = (c * u - s) / z, yy = v / z;
          const value = 110 + 26 * Math.sin(38 * xx + 9 * yy) +
            25 * Math.cos(51 * yy + 13 * xx) + 18 * Math.sin(39 * xx * yy);
          const i = (y * base.width + x) * 4;
          data[i] = value + 16 * Math.sin(9 * xx);
          data[i + 1] = value;
          data[i + 2] = value + 15 * Math.cos(11 * yy);
          data[i + 3] = 255;
        }
        return base;
      };
    });
    await page.locator("#settings-toggle").click();
    await page.locator("#target-orientation").selectOption("landscape");
    await page.locator("#show-fps").check();
    await page.locator("#custom-width").fill("40");
    await page.locator("#custom-height").fill("12");
    await page.locator("#set-format").click();
    await page.locator('[data-close="settings"]').click();
    await page.locator("#focal").fill("20");
    await page.locator("#focal").dispatchEvent("change");
    await page.locator("#scan-start").click();
    await check.poll(async () => (await counts(page)).accepted).toBe(1);
    const before = await counts(page);
    await orient(page, 0, .3);
    await page.waitForTimeout(2600);
    const left = await counts(page);
    const movingFps = parseFloat(await page.locator("#finder-fps").textContent());
    expect(movingFps).toBeGreaterThan(0);
    expect(left.accepted).toBeGreaterThan(before.accepted + 1);
    expect(left.lastCapture.settling).toBe(true); // It really was moving.
    await orient(page, 24, -.3);
    await check.poll(async () => Number(await page.locator("#scan-progress").getAttribute("value")),
      { timeout: 15000 }).toBeGreaterThan(.98);
    await orient(page, 0);
    await page.locator("#scan-finish").click();
    await check(page.locator("#scan-status")).toContainText("二次构图");
    await check.poll(async () => (await pixels(page)).empty).toBeLessThan(.01);
    const done = await counts(page);
    expect(done.trackingDecode).toBe(0);
    expect(done.register).toBeGreaterThanOrEqual(3);
    expect(done.frameDecode).toBeLessThan(done.poseCaptures / 2);
    const processing = done.processing.filter(Number.isFinite).sort((a, b) => a - b);
    console.log(`PASS continuous app panorama: ${done.accepted} keyframes, ${(done.lastCapture.coverage * 100).toFixed(1)}% coverage, ${movingFps} FPS; worker median ${processing[Math.floor(processing.length / 2)].toFixed(1)} ms, P95 ${processing[Math.floor(processing.length * .95)].toFixed(1)} ms`);
    await page.screenshot({ path: path.join(snapshots, "continuous-complete.png") });

    // Moving while the page is paused must retain the confirmed world origin.
    await orient(page, 10);
    await page.waitForTimeout(150);
    await page.evaluate(() => {
      Object.defineProperty(document, "hidden", { configurable: true, value: true });
      document.dispatchEvent(new Event("visibilitychange"));
    });
    await orient(page, 35);
    await page.waitForTimeout(100);
    await page.evaluate(() => {
      Object.defineProperty(document, "hidden", { configurable: true, value: false });
      document.dispatchEvent(new Event("visibilitychange"));
    });
    await check.poll(async () => {
      const r = (await counts(page)).lastCapture.orientation;
      return Math.atan2(r[2], r[8]) * 180 / Math.PI;
    }).toBeCloseTo(35, 2);
    console.log("PASS panorama visibility: background movement retains its original orientation");
  } finally { await context.close(); }
}

async function fallbackScan() {
  const { context, page } = await openCamera({ sensor: false });
  try {
    await page.locator("#scan-start").click();
    await check(page.locator("#scan-panel")).toBeVisible();
    await check.poll(async () => (await counts(page)).accepted).toBe(1);
    await check(page.locator("#scan-status")).toContainText("图像配准");
    await check(page.locator("#scan-level")).toBeHidden();
    await check.poll(async () => (await counts(page)).trackingDecode).toBeGreaterThan(1);
    const tracked = await counts(page);
    expect(tracked.analysisEdges).toContain(160);
    expect(tracked.analysisEdges).toContain(640);
    expect(tracked.trackingDecode).toBeLessThan(tracked.fallbackCaptures);
    await page.locator("#scan-finish").click();
    const before = await pixels(page), count = await counts(page);
    const box = await page.locator("#view").boundingBox();
    await page.mouse.move(box.x + box.width * .65, box.y + box.height * .5);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * .15, box.y + box.height * .5, { steps: 8 });
    await page.mouse.up();
    await check.poll(async () => (await pixels(page)).hash).not.toBe(before.hash);
    await check(page.locator("#scan-home")).toBeVisible();
    await page.waitForTimeout(450);
    const dragged = await counts(page);
    expect(dragged.write).toBe(count.write);
    expect(dragged.accepted).toBe(count.accepted);
    expect(dragged.fallbackCaptures).toBeGreaterThan(0);
    await page.locator("#scan-cancel").click();
    await check(page.locator("#scan-panel")).toBeHidden();
    const exited = await counts(page);
    await check.poll(async () => (await counts(page)).analysis).toBeGreaterThan(exited.analysis);
    await check(page.locator("#meter-reading")).toContainText("EV100");
    console.log("PASS no-sensor panorama: thumbnail tracking, drag reframing, no post-finish writes, live metering restored");
  } finally {
    await context.close();
  }
}

async function preparationRaceScan() {
  const { context, page } = await openCamera();
  try {
    await page.evaluate(async () => {
      const { PanoramaEngine } = await import("./panorama-engine.js");
      const create = PanoramaEngine.create;
      const qa = globalThis.panoramaControlsQA = { exposureMode: "continuous", whiteBalanceMode: "continuous", applies: 0 };
      let calls = 0;
      PanoramaEngine.create = async function (...args) {
        if (++calls === 1) await new Promise(resolve => { qa.releasePreparationA = resolve; });
        return create.apply(this, args);
      };
      const settings = MediaStreamTrack.prototype.getSettings;
      const capabilities = MediaStreamTrack.prototype.getCapabilities;
      const constraints = MediaStreamTrack.prototype.getConstraints;
      MediaStreamTrack.prototype.getSettings = function () {
        return { ...settings.call(this), exposureMode: qa.exposureMode,
          whiteBalanceMode: qa.whiteBalanceMode, colorTemperature: 5000 };
      };
      MediaStreamTrack.prototype.getCapabilities = function () {
        return { ...capabilities.call(this), exposureMode: ["continuous", "manual"],
          whiteBalanceMode: ["continuous", "manual"], exposureTime: { min: 1, max: 10000 },
          iso: { min: 3, max: 6400 }, colorTemperature: { min: 2500, max: 8000 } };
      };
      MediaStreamTrack.prototype.getConstraints = function () {
        return qa.constraints || constraints.call(this);
      };
      // Simulate only the camera-control API; the actual video track continues.
      MediaStreamTrack.prototype.applyConstraints = async function (value) {
        qa.applies++;
        qa.constraints = structuredClone(value);
        const controls = Object.assign({}, value, ...(value.advanced || []));
        for (const key of ["exposureMode", "whiteBalanceMode"])
          if (controls[key]) qa[key] = controls[key];
      };
    });
    await page.locator("#scan-start").click();
    await check.poll(() => page.evaluate(() => globalThis.panoramaControlsQA.exposureMode)).toBe("manual");
    // Cancel A during its delayed initialization, immediately prepare B on the
    // same track, then let the stale A finish after B is already collecting.
    await page.locator("#focal").fill("10");
    await page.locator("#focal").dispatchEvent("change");
    await page.locator("#scan-start").click();
    await check(page.locator("#scan-panel")).toBeVisible();
    await check.poll(async () => (await counts(page)).accepted).toBe(1);
    await check(page.locator("#scan-status")).toHaveAttribute("data-exposure-locked", "true");
    await check(page.locator("#scan-status")).toHaveAttribute("data-white-balance-locked", "true");
    const applies = await page.evaluate(() => globalThis.panoramaControlsQA.applies);
    await page.evaluate(() => globalThis.panoramaControlsQA.releasePreparationA());
    await page.waitForTimeout(600);
    const active = await page.evaluate(() => globalThis.panoramaControlsQA);
    expect(active.exposureMode).toBe("manual");
    expect(active.whiteBalanceMode).toBe("manual");
    expect(active.applies).toBe(applies);
    await page.locator("#scan-cancel").click();
    await check.poll(() => page.evaluate(() => globalThis.panoramaControlsQA.exposureMode)).toBe("continuous");
    await check.poll(() => page.evaluate(() => globalThis.panoramaControlsQA.whiteBalanceMode)).toBe("continuous");
    console.log("PASS preparing/cancel/restart race: stale initialization cannot release the new AE/AWB hold");
  } finally { await context.close(); }
}

async function unavailableEngineScan() {
  const { context, page } = await openCamera();
  try {
    await page.evaluate(async () => {
      const { PanoramaEngine } = await import("./panorama-engine.js");
      PanoramaEngine.create = async () => { throw new Error("Simulated unsupported worker"); };
    });
    await page.locator("#scan-start").click();
    await check(page.locator("#scan-panel")).toBeVisible();
    await check.poll(async () => (await counts(page)).accepted).toBe(1);
    await check(page.locator("#scan-status")).toContainText("已停稳");
    expect((await counts(page)).processing).toEqual([]);
    await page.locator("#scan-cancel").click();
    const before = await counts(page);
    await check.poll(async () => (await counts(page)).analysis).toBeGreaterThan(before.analysis);
    console.log("PASS unavailable continuous engine: stationary capture and live metering remain usable");
  } finally { await context.close(); }
}

async function englishScan() {
  const { context, page } = await openCamera({ language: "en" });
  try {
    await page.locator("#scan-start").click();
    await check.poll(async () => (await counts(page)).accepted).toBe(1);
    await check(page.locator("#scan-status")).toContainText("Capturing continuously");
    expect(await page.locator("#scan-status").textContent()).not.toMatch(/[\u3400-\u9fff]/);
    await page.locator("#scan-finish").click();
    await orient(page, 180);
    await check(page.locator("#scan-home")).toBeVisible();
    await check(page.locator("#scan-home span")).toHaveText("Return to panorama center");
    await check(page.locator("#meter-reading")).toHaveText("Uncaptured area · No valid reading");
    await check(page.locator("#scan-status")).toContainText("Reframe");
    await page.screenshot({ path: path.join(snapshots, "english-reframe.png") });
    console.log("PASS English panorama capture state, reframing, home arrow and unavailable metering");
  } finally {
    await context.close();
  }
}

try {
  browser = await chromium.launch({
    executablePath: process.env.LIGHTDATA_CHROME || (process.platform === "win32"
      ? "C:/Program Files/Google/Chrome/Application/chrome.exe" : undefined),
    headless: true,
    args: ["--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream",
      `--use-file-for-fake-video-capture=${fixtureFile}`],
  });
  await poseScan();
  await continuousScan();
  await fallbackScan();
  await preparationRaceScan();
  await unavailableEngineScan();
  await englishScan();
  expect(errors).toEqual([]);
  console.log("PASS panorama app integration; no uncaught page errors");
} finally {
  await browser?.close();
  await new Promise((resolve) => server.close(resolve));
  // This path is created by mkdtemp above and must remain under the OS temp root.
  if (!path.resolve(fixtureDirectory).startsWith(path.resolve(os.tmpdir()) + path.sep))
    throw new Error("Refusing to remove a fixture outside the temporary directory");
  await fs.rm(fixtureDirectory, { recursive: true, force: true });
}

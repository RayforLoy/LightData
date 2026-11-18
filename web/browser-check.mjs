import { chromium, expect } from "@playwright/test";
import http from "node:http";
import fs from "node:fs/promises";
import path from "node:path";
import os from "node:os";
import { fileURLToPath } from "node:url";

const root = path.dirname(fileURLToPath(import.meta.url));
const mime = {
  ".html": "text/html",
  ".js": "application/javascript",
  ".css": "text/css",
  ".json": "application/json",
  ".png": "image/png",
  ".mp3": "audio/mpeg",
};
const server = http.createServer(async (req, res) => {
  try {
    const pathname = decodeURIComponent(
      new URL(req.url, "http://localhost").pathname,
    );
    const file = path.resolve(
      root,
      "." + (pathname === "/" ? "/index.html" : pathname),
    );
    if (!file.startsWith(root + path.sep)) throw new Error();
    res.setHeader(
      "Content-Type",
      mime[path.extname(file)] || "application/octet-stream",
    );
    res.end(await fs.readFile(file));
  } catch {
    res.writeHead(404);
    res.end();
  }
});
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const base =
  process.env.LIGHTDATA_URL || `http://127.0.0.1:${server.address().port}`;
const executablePath =
  process.env.LIGHTDATA_CHROME ||
  (process.platform === "win32"
    ? "C:/Program Files/Google/Chrome/Application/chrome.exe"
    : undefined);
// Keep the real browser media pipeline, with a fixed scene. Chromium's default
// animated test pattern changes brightness while we verify ISO/shutter updates.
const fixtureDirectory = await fs.mkdtemp(path.join(os.tmpdir(), "lightdata-camera-"));
const fixtureFile = path.join(fixtureDirectory, "scene.y4m");
const width = 1280, height = 720, area = width * height;
const yuv = Buffer.alloc(area * 3 / 2, 128);
for (let y = 0; y < height; y++) {
  for (let x = 0; x < width; x++) {
    yuv[y * width + x] = 42 + Math.floor(145 * x / width) + (((x >> 5) + (y >> 5)) % 2) * 24;
  }
}
await fs.writeFile(fixtureFile, Buffer.concat([
  Buffer.from(`YUV4MPEG2 W${width} H${height} F20:1 Ip A1:1 C420jpeg\nFRAME\n`), yuv,
]));
const browser = await chromium.launch({
  executablePath,
  headless: true,
  args: [
    "--use-fake-device-for-media-stream",
    "--use-fake-ui-for-media-stream",
    `--use-file-for-fake-video-capture=${fixtureFile}`,
  ],
});
const context = await browser.newContext({
  locale: "zh-CN",
  viewport: { width: 430, height: 932 },
  permissions: ["camera"],
  deviceScaleFactor: 1,
});
const page = await context.newPage();
const errors = [];
page.on("pageerror", (error) => errors.push(error.message));
const snapshots =
  process.env.LIGHTDATA_SHOTS || path.join(os.tmpdir(), "LightDataWebQA");
await fs.mkdir(snapshots, { recursive: true });
async function settings() {
  await page.locator("#settings-toggle").click();
  await expect(page.locator("#settings")).toBeVisible();
}
async function close() {
  await page.locator("[data-close=settings]").click();
}
async function notice() {
  if (await page.locator("#notice").isVisible())
    await page.locator("#notice-ok").click();
}
async function valueReading() {
  return page
    .locator("#meter-reading")
    .textContent()
    .then((v) => Number.parseFloat(v));
}
async function openMore() {
  await page.locator("#more-toggle").click();
  await expect(page.locator("#more")).toBeVisible();
}
const closeMore = () => page.locator("[data-close=more]").click();
const changeNumber = async (id, value) => {
  await page.locator(id).fill(String(value));
  await page.locator(id).dispatchEvent("change");
};
const shutterSeconds = async () => {
  const text = await page.locator("#exposure-table button.active span").first().textContent();
  return text.includes("/") ? 1 / Number(text.split("/")[1]) : parseFloat(text);
};

async function checkMoreTools() {
  await page.evaluate(() => {
    window.dispatchEvent(new DeviceOrientationEvent("deviceorientation", { beta: 90, gamma: 0 }));
  });
  await expect(page.locator("#finder-level")).toBeVisible();
  const original = await shutterSeconds();
  const originalLimits = await page.locator("#thresholds").textContent();
  await openMore();
  await expect(page.locator("#level-enabled")).toBeChecked();
  await page.locator("#level-enabled").uncheck();
  await expect(page.locator("#finder-level")).toBeHidden();
  await page.locator("#level-enabled").check();
  await changeNumber("#filter-factor", 4);
  await expect(page.locator("#filter-stops")).toHaveValue("2");
  await changeNumber("#bellows-draw", 70);
  await expect(page.locator("#loss-info")).toHaveText("总光损 +4 EV");
  await page.screenshot({ path: path.join(snapshots, "more-optical-loss.png") });
  expect(Math.abs(Math.log2(await shutterSeconds() / original) - 4)).toBeLessThan(.4);
  const correctedLimit = (await page.locator("#thresholds").textContent()).match(/[\d.]+/g).map(Number);
  expect(Math.abs(correctedLimit[0] - Number(originalLimits.match(/[\d.]+/)[0]))).toBeLessThanOrEqual(.3);
  await changeNumber("#filter-stops", 0);
  await changeNumber("#bellows-draw", 0);

  await page.locator("#framing-tools summary").click();
  await page.locator("#snapshot-save").click();
  await expect(page.locator("#snapshot-list > div")).toHaveCount(1);
  await page.locator("#snapshot-list > div > button").first().click();
  await expect(page.locator("#framing-overlay img")).toBeVisible();
  await expect.poll(() => page.locator("#framing-overlay img").evaluate((img) => img.naturalWidth)).toBeGreaterThan(0);
  await openMore();
  await page.locator("#overlay-opacity").fill("60");
  await expect(page.locator("#framing-overlay")).toHaveCSS("opacity", "0.6");
  await page.locator("#snapshot-hide").click();
  await expect(page.locator("#framing-overlay")).toBeHidden();

  await page.locator("#meter-sound").uncheck();
  await page.locator("#meter-sound-file").setInputFiles(path.join(root, "audio", "sony_focus.mp3"));
  await expect(page.locator("#toast")).toContainText("已保存测光确认音");
  await page.locator("#meter-sound-restore").click();
  await expect(page.locator("#notice-text")).toContainText("iainmccurdy");
  await expect(page.locator("#notice-link")).toHaveAttribute("href", /freesound\.org.*613889/);
  await notice();
  await closeMore();
  await page.locator("#single").click();
  expect(await page.evaluate(() => globalThis.lastMeterSound)).toBeUndefined();
  await openMore();
  await page.locator("#meter-sound").check();
  await closeMore();
  await page.locator("#single").click();
  await expect.poll(() => page.evaluate(() => globalThis.lastMeterSound)).toMatch(/\/audio\/sony_focus\.mp3$/);
  const bundled = await page.request.get(new URL("audio/sony_focus.mp3", base).href);
  expect(bundled.ok()).toBe(true);
  expect((await bundled.body()).length).toBe(9408);
  await page.locator("#continuous").click();

  await page.locator("#spots-toggle").click();
  await notice();
  await page.locator("#view").click();
  await expect(page.locator("#spots .spot")).toHaveCount(1);
  await page.locator("#spot-list button").first().click();
  await page.locator("#placement-zones").getByRole("button", { name: "VIII", exact: true }).click();
  await expect(page.locator("#exposure-table-label")).toHaveText("点 1 → Zone VIII");
  expect(await shutterSeconds()).toBeGreaterThan(original * 2);
  await page.locator("#spot-list button").first().click();
  await page.locator("#placement-clear").click();
  await expect(page.locator("#exposure-table-label")).toHaveText("等效曝光");
  expect(await shutterSeconds()).toBe(original);

  await openMore();
  await page.locator("#ground-tools summary").click();
  await expect(page.locator("#ground-reference option").first()).toContainText("点 1");
  await page.locator("#ground-delay").selectOption("3");
  await page.locator("#ground-calibrate").click();
  await expect(page.locator("#ground-capture")).toBeVisible();
  await expect(page.locator("#ground-profile")).toContainText("已校准", { timeout: 8500 });
  await expect(page.locator("#ground-capture")).toBeHidden();
  await page.locator("#ground-measure").click();
  await page.locator("#ground-abort").click();
  await expect(page.locator("#ground-result")).toBeEmpty();
  const before = await valueReading();
  await page.evaluate(() => { globalThis.testCameraSettings.iso = 400; });
  await expect.poll(valueReading).toBeLessThan(before - 1.8);
  await page.locator("#ground-measure").click();
  await expect(page.locator("#ground-result")).toContainText("EV100", { timeout: 8500 });
  await page.evaluate(() => { globalThis.testCameraSettings.iso = 100; });
  await expect.poll(valueReading).toBeGreaterThan(before - .2);
  await page.locator("#ground-compare").click();
  await expect(page.locator("#ground-pair")).toBeVisible();
  await page.locator("#view").click();
  await expect(page.locator("#ground-confirm")).toBeVisible();
  await page.screenshot({ path: path.join(snapshots, "ground-pair.png") });
  await page.locator("#ground-apply").click();
  await expect(page.locator("#ground-pair")).toBeHidden();
  await openMore();
  await expect(page.locator("#loss-source")).toHaveValue("measured");
  expect(Math.abs(parseFloat((await page.locator("#loss-info").textContent()).split("+")[1]) - 2)).toBeLessThan(.15);
  await page.locator("#loss-source").selectOption("geometric");
  await closeMore();

  await settings();
  await page.locator("#film-select").selectOption({ label: "PORTRA 400 · ISO 400" });
  await close();
  await page.evaluate(() => {
    Object.assign(globalThis.testCameraSettings, { exposureTime: 10000, iso: 6400 });
  });
  await expect(page.locator("#dim-meter")).toBeVisible();
  await expect(page.locator("#reciprocity-note")).toContainText("数据来源网络");
  await expect(page.locator("#reciprocity-source")).toHaveAttribute("href", /metergeist\.com/);
  await page.evaluate(() => {
    Object.assign(globalThis.testCameraSettings, { exposureTime: 100, iso: 100 });
  });
  await expect(page.locator("#dim-meter")).toBeHidden();
}
try {
  await page.addInitScript(() => {
    if (typeof DeviceOrientationEvent.requestPermission === "function")
      DeviceOrientationEvent.requestPermission = async () => "granted";
    if (!localStorage.getItem("lightdata.web.v1")) {
      // A legacy browser stored linear as the old default, with no scale version.
      localStorage.setItem("lightdata.web.v1", JSON.stringify({ log: false }));
      localStorage.setItem("lightdata.test-unrelated", "keep");
    }
    const original = MediaStreamTrack.prototype.getSettings;
    MediaStreamTrack.prototype.getSettings = function () {
      // Chromium 145 can rotate fake-device IDs on reload. This metadata fixture
      // represents one camera; camera-isolation checks explicitly override its ID.
      return {
        ...original.call(this),
        deviceId: "lightdata-synthetic-camera",
        ...globalThis.testCameraSettings,
      };
    };
    const play = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
      if (this instanceof HTMLAudioElement) {
        globalThis.lastMeterSound = this.src;
        return Promise.resolve();
      }
      return play.call(this);
    };
  });
  await page.goto(base);
  await expect(page.locator("#film-select option")).toHaveCount(168);
  await expect(page.locator("#format option")).toHaveCount(17);
  await expect(page.locator("#pixel-label")).toHaveText("像素 log");
  await expect(page.locator(".toolbar a[href*=releases]")).toHaveAttribute(
    "href",
    /releases\/tag\/0\.2\.0-beta$/,
  );
  await expect(page.locator("#camera-setup")).toBeVisible();
  await expect(page.locator("#setup-brand")).toHaveValue("");
  await expect(page.locator("#setup-phone")).toBeDisabled();
  await expect(page.locator("#setup-lens")).toBeDisabled();
  await page.locator("#setup-save").click();
  await expect(page.locator("#setup-error")).toContainText("请填写");
  await page.locator("#setup-aperture").fill("2.3");
  await page.locator("#setup-hfov").fill("70");
  await expect(page.locator("#setup-error")).toBeEmpty();
  await page.screenshot({ path: path.join(snapshots, "lens-onboarding.png") });
  await page.locator("#setup-save").click();
  await expect(page.locator("#live-state")).toContainText("LIVE");
  await expect(page.locator("#camera-setup")).toBeHidden();
  await expect(page.locator("#meter-reading")).toContainText("相对亮度");
  await settings();
  await expect(page.locator("#capture-mode")).toHaveValue("auto");
  await expect(page.locator("#capture-aperture")).toHaveValue("2.3");
  await expect(page.locator("#camera-hfov")).toHaveValue("70");
  await page.locator("#capture-aperture").fill("");
  await page.locator("#capture-aperture").dispatchEvent("change");
  await expect(page.locator("#log-counts")).toBeChecked();
  await page.evaluate(() => {
    globalThis.testCameraSettings = {
      exposureMode: "continuous",
      exposureTime: 299.94234,
      iso: 250,
    };
  });
  await expect(page.locator("#finder-fps")).toBeHidden();
  await page.locator("#show-fps").check();
  await expect(page.locator("#finder-fps")).toHaveText(/^[1-9][0-9]* FPS$/);
  console.log("Live view, 20 FPS fixture:", await page.locator("#finder-fps").textContent());
  await page.locator("#show-fps").uncheck();
  await expect(page.locator("#finder-fps")).toBeHidden();
  await expect(page.locator("#capture-iso")).toHaveValue("250");
  await expect(page.locator("#capture-time")).toHaveValue("0.029994234");
  await expect(page.locator("#capture-iso")).not.toBeEditable();
  await expect(page.locator("#capture-time")).not.toBeEditable();
  await expect(page.locator("#capture-info")).toContainText("请填写手机光圈");
  await expect(page.locator("#meter-reading")).toContainText("相对亮度");
  await page.locator("#capture-aperture").fill("2.3");
  await page.locator("#capture-aperture").dispatchEvent("change");
  await expect(page.locator("#meter-reading")).toContainText("EV100");
  await expect(page.locator("#capture-info")).toContainText("ISO 250");
  await expect(page.locator("#capture-info")).toContainText("用于场景 EV100");
  await expect(page.locator("#capture-fixed-controls")).toBeHidden();
  await page.screenshot({ path: path.join(snapshots, "capture-metadata.png") });
  await page.evaluate(() => {
    globalThis.testCameraSettings.deviceId = "lightdata-other-camera";
  });
  await expect(page.locator("#capture-aperture")).toHaveValue("");
  await expect(page.locator("#meter-reading")).toContainText("相对亮度");
  await expect(page.locator("#camera-setup")).toBeVisible();
  await expect(page.locator("#setup-aperture")).toHaveValue("");
  await page.locator("#setup-aperture").fill("1.8");
  await page.locator("#setup-fov-mode").selectOption("equivalent");
  await page.locator("#setup-equivalent").fill("23");
  await expect(page.locator("#setup-field-info")).toContainText("当前预览");
  await page.locator("#setup-save").click();
  await expect(page.locator("#settings")).toBeVisible();
  await expect(page.locator("#capture-aperture")).toHaveValue("1.8");
  await expect(page.locator("#camera-equivalent")).toHaveValue("23");
  const unzoomed = Number(await page.locator("#camera-hfov").inputValue());
  await page.evaluate(() => {
    globalThis.testCameraSettings.zoom = 2;
  });
  await expect
    .poll(async () => Number(await page.locator("#camera-hfov").inputValue()))
    .toBeLessThan(unzoomed - 15);
  await expect(page.locator("#camera-setup")).toBeHidden();
  await expect(page.locator("#capture-aperture")).toHaveValue("1.8");
  await page.evaluate(() => {
    delete globalThis.testCameraSettings.deviceId;
    delete globalThis.testCameraSettings.zoom;
  });
  await expect(page.locator("#capture-aperture")).toHaveValue("2.3");
  await expect(page.locator("#meter-reading")).toContainText("EV100");
  await expect(page.locator("#camera-setup")).toBeHidden();
  await page.evaluate(() => {
    globalThis.testCameraSettings.deviceId = "lightdata-deferred-camera";
  });
  await expect(page.locator("#camera-setup")).toBeVisible();
  await page.locator("#setup-skip").click();
  await expect(page.locator("#capture-aperture")).toHaveValue("");
  await page.waitForTimeout(650);
  await expect(page.locator("#camera-setup")).toBeHidden();
  await page.evaluate(() => {
    delete globalThis.testCameraSettings.deviceId;
  });
  await expect(page.locator("#capture-aperture")).toHaveValue("2.3");
  await expect(page.locator("#meter-reading")).toContainText("EV100");
  const screenshotReading = await valueReading();
  await page.evaluate(() => {
    globalThis.testCameraSettings.iso = 500;
  });
  await expect(page.locator("#capture-iso")).toHaveValue("500");
  await expect.poll(valueReading).toBeLessThan(screenshotReading - 0.8);
  await page.locator("#capture-mode").selectOption("fixed");
  await expect(page.locator("#meter-reading")).toContainText("相对亮度");
  await page.locator("#capture-mode").selectOption("auto");
  await expect(page.locator("#meter-reading")).toContainText("EV100");
  await page.evaluate(() => {
    globalThis.testCameraSettings = {
      exposureMode: "continuous",
      exposureTime: undefined,
      iso: undefined,
    };
  });
  await expect(page.locator("#capture-iso")).toHaveValue("");
  await expect(page.locator("#capture-time")).toHaveValue("");
  await expect(page.locator("#meter-reading")).toContainText("相对亮度");
  await page.evaluate(() => {
    globalThis.testCameraSettings = {
      exposureMode: "continuous",
      exposureTime: 100,
      iso: 100,
    };
  });
  await expect(page.locator("#capture-time")).toHaveValue("0.01");
  await expect(page.locator("#capture-iso")).toHaveValue("100");
  await close();
  await expect(page.locator("#meter-reading")).toContainText("EV100");
  const first = await valueReading();
  await page.evaluate(
    () => (globalThis.testCameraSettings.exposureTime = 1000),
  );
  await expect.poll(valueReading).toBeLessThan(first - 2.5);
  await page.locator("#single").click();
  const held = await page.locator("#exposure-table").textContent();
  await page.evaluate(() => (globalThis.testCameraSettings.exposureTime = 100));
  await page.waitForTimeout(850);
  expect(await page.locator("#exposure-table").textContent()).toBe(held);
  await page.locator("#continuous").click();
  // Reset the sound monitor after the separate continuous/single metering check.
  await page.evaluate(() => { delete globalThis.lastMeterSound; });
  await checkMoreTools();
  await expect
    .poll(() => page.locator("#exposure-table").textContent())
    .not.toBe(held);
  await settings();
  await page
    .locator("#film-select")
    .selectOption({ label: "PRO 160S · ISO 160" });
  await expect(page.locator("#custom-iso")).toHaveValue("160");
  await expect(page.locator("#custom-iso")).toBeDisabled();
  await page.locator("#settings-iso-lock").click();
  await page.locator("#custom-iso").fill("3");
  await page.locator("#set-iso").click();
  await expect(page.locator("#pair-iso")).toContainText("ISO 3");
  await page
    .locator("#film-select")
    .selectOption({ label: "HP5 PLUS · ISO 400" });
  await expect(page.locator("#custom-iso")).toHaveValue("400");
  await page.locator("#camera-hfov").fill("70");
  await page.locator("#camera-hfov").dispatchEvent("change");
  await close();
  const apertureBefore = await page.evaluate(
    () => JSON.parse(localStorage.getItem("lightdata.web.v1")).manual.a,
  );
  await page.locator("#exposure-table button").first().click();
  expect(
    await page.evaluate(
      () => JSON.parse(localStorage.getItem("lightdata.web.v1")).manual.a,
    ),
  ).not.toBe(apertureBefore);
  await page.locator("#preview-toggle").click();
  await notice();
  await page.locator("#spots-toggle").click();
  await notice();
  const rect = await page.locator("#view").boundingBox();
  await page
    .locator("#view")
    .click({ position: { x: rect.width * 0.35, y: rect.height * 0.4 } });
  await expect(page.locator("#spots .spot")).toHaveCount(1);
  await page
    .locator("#view")
    .click({ position: { x: rect.width * 0.6, y: rect.height * 0.5 } });
  await expect(page.locator("#spots .spot")).toHaveCount(2);
  const colors = await page
    .locator("#spots .spot")
    .evaluateAll((nodes) => nodes.map((n) => n.style.color));
  expect(colors[0]).not.toBe(colors[1]);
  await settings();
  await page.locator("#film-select").selectOption({ label: "PORTRA 160 · ISO 160" });
  const frozenValues = () => page.evaluate(() =>
    ["meter-reading", "thresholds", "spot-list", "exposure-table"].map(id => document.getElementById(id).textContent));
  const frozenPixels = () => page.locator("#view").evaluate(canvas => canvas.toDataURL());
  const beforePixels = await frozenPixels();
  const beforeLut = await frozenValues();
  const frozenWidth = await page.locator("#view").getAttribute("width");
  await page.locator("#preview-mode").selectOption("lut");
  await expect(page.locator("#lut-info")).toContainText("Spektrafilm");
  await expect.poll(frozenPixels).not.toBe(beforePixels);
  await page.locator("#preview-scale").selectOption("0.25");
  expect(await frozenValues()).toEqual(beforeLut);
  expect(await page.locator("#view").getAttribute("width")).toBe(frozenWidth);
  await page.locator("#preview-mode").selectOption("luminance");
  await page.locator("#preview-scale").selectOption("0.5");
  await page.locator("#film-select").selectOption({ label: "HP5 PLUS · ISO 400" });
  await close();
  const axisBefore = await page.locator("#chart").getAttribute("aria-label");
  const chart = await page.locator("#chart").boundingBox();
  const cdp = await context.newCDPSession(page);
  const x = chart.x + chart.width * 0.5,
    y = chart.y + chart.height * 0.5;
  await cdp.send("Input.dispatchTouchEvent", {
    type: "touchStart",
    touchPoints: [{ x, y, id: 1 }],
  });
  await cdp.send("Input.dispatchTouchEvent", {
    type: "touchMove",
    touchPoints: [{ x: x + 30, y, id: 1 }],
  });
  await cdp.send("Input.dispatchTouchEvent", {
    type: "touchEnd",
    touchPoints: [],
  });
  expect(await page.locator("#chart").getAttribute("aria-label")).not.toBe(
    axisBefore,
  );
  const panAxis = await page.locator("#chart").getAttribute("aria-label");
  await cdp.send("Input.dispatchTouchEvent", {
    type: "touchStart",
    touchPoints: [
      { x: x - 20, y: y - 25, id: 1 },
      { x: x + 20, y: y + 25, id: 2 },
    ],
  });
  await cdp.send("Input.dispatchTouchEvent", {
    type: "touchMove",
    touchPoints: [
      { x: x - 35, y: y - 45, id: 1 },
      { x: x + 35, y: y + 45, id: 2 },
    ],
  });
  await cdp.send("Input.dispatchTouchEvent", {
    type: "touchEnd",
    touchPoints: [],
  });
  expect(await page.locator("#chart").getAttribute("aria-label")).not.toBe(
    panAxis,
  );
  await page.locator("#hist-reset").click();
  await page.screenshot({ path: path.join(snapshots, "portrait.png") });
  await page.locator("#spots-toggle").click();
  await page.locator("#focal").fill("8");
  await page.locator("#focal").dispatchEvent("change");
  await expect(page.locator("#scan-start")).toBeVisible();
  await page.locator("#scan-start").click();
  await expect(page.locator("#scan-panel")).toBeVisible();
  await expect(page.locator("#scan-status")).toContainText("覆盖");
  await page.locator("#scan-finish").click();
  await expect(page.locator("#scan-status")).toContainText("二次构图");
  await page.locator("#scan-cancel").click();
  await page.locator("#focal").fill("35");
  await page.locator("#focal").dispatchEvent("change");
  await settings();
  await page.locator("#custom-width").fill("5");
  await page.locator("#custom-height").fill("4");
  await page.locator("#format-unit").selectOption("inch");
  await page.locator("#set-format").click();
  await close();
  await page.locator("#format").selectOption("4");
  await page.locator("#format").selectOption("custom");
  expect(
    await page.evaluate(
      () => JSON.parse(localStorage.getItem("lightdata.web.v1")).format.w,
    ),
  ).toBe(127);
  await page.locator("#format").selectOption("4");
  await settings();
  await page.locator("summary").filter({ hasText: "自定义预设" }).click();
  await page.locator("#preset-name").fill("网页测试片");
  await page.locator("#preset-iso").fill("64");
  await page.locator("#preset-form button[type=submit]").click();
  await expect(page.locator("#custom-iso")).toHaveValue("64");
  const pendingDownload = page.waitForEvent("download");
  await page.locator("#presets-export").click();
  const file = await pendingDownload;
  const exported = JSON.parse(await fs.readFile(await file.path(), "utf8"));
  expect(exported.format).toBe("lightdata.custom-presets");
  expect(exported.presets[0].name).toBe("网页测试片");
  exported.presets[0].iso = 80;
  await page.locator("#presets-file").setInputFiles({
    name: "presets.json",
    mimeType: "application/json",
    buffer: Buffer.from(JSON.stringify(exported)),
  });
  await expect(page.locator("#custom-iso")).toHaveValue("80");
  await expect(page.locator("#format option[value=custom]")).toHaveCount(1);
  const before = await page.evaluate(
    () => JSON.parse(localStorage.getItem("lightdata.web.v1")).presets,
  );
  await page.locator("#presets-file").setInputFiles({
    name: "bad.json",
    mimeType: "application/json",
    buffer: Buffer.from("{invalid"),
  });
  expect(
    await page.evaluate(
      () => JSON.parse(localStorage.getItem("lightdata.web.v1")).presets,
    ),
  ).toBe(before);
  await page.locator("#log-counts").uncheck();
  await close();
  await page.reload();
  await settings();
  await expect(page.locator("#log-counts")).not.toBeChecked();
  await expect(page.locator("#pixel-label")).toHaveText("像素");
  await expect(page.locator("#custom-iso")).toHaveValue("80");
  await close();
  await page.locator("#start-camera").click();
  await expect(page.locator("#live-state")).toContainText("LIVE");
  await expect(page.locator("#image-frame")).toBeVisible();
  await openMore();
  await page.locator("#ground-tools summary").click();
  await expect(page.locator("#ground-profile")).toContainText("已校准");
  await page.locator("#framing-tools summary").click();
  await expect(page.locator("#snapshot-list > div")).toHaveCount(1);
  await expect(page.locator("#overlay-opacity")).toHaveValue("60");
  await page.locator("#snapshot-list > div > button").last().click();
  await expect(page.locator("#snapshot-list > div")).toHaveCount(0);
  await closeMore();
  await settings();
  await expect(page.locator("#capture-mode")).toHaveValue("auto");
  await expect(page.locator("#capture-aperture")).toHaveValue("2.3");
  await expect(page.locator("#camera-setup")).toBeHidden();
  await page.evaluate(() => {
    globalThis.testCameraSettings = {
      exposureMode: "continuous",
      exposureTime: 299.94234,
      iso: 250,
    };
  });
  await expect(page.locator("#capture-iso")).toHaveValue("250");
  await expect(page.locator("#meter-reading")).toContainText("EV100");
  await close();
  await page.setViewportSize({ width: 1200, height: 1380 });
  await expect(page.locator("body")).toHaveClass(/side/);
  await page.screenshot({ path: path.join(snapshots, "fold-portrait.png") });
  await page.setViewportSize({ width: 1380, height: 1200 });
  await expect(page.locator("body")).toHaveClass(/landscape/);
  expect(
    (await page.locator("body").getAttribute("class")).includes("side"),
  ).toBe(false);
  await page.screenshot({ path: path.join(snapshots, "fold-landscape.png") });
  await page.setViewportSize({ width: 932, height: 430 });
  await expect(page.locator("body")).toHaveClass(/side/);
  await page.screenshot({ path: path.join(snapshots, "landscape.png") });
  await settings();
  await page.locator("#resize-layout").check();
  await close();
  const split = await page.locator("#split-main").boundingBox();
  await page.mouse.move(split.x + split.width / 2, split.y + split.height / 2);
  await page.mouse.down();
  await page.mouse.move(
    split.x + split.width / 2 + 30,
    split.y + split.height / 2,
  );
  await page.mouse.up();
  expect(
    await page.evaluate(
      () => JSON.parse(localStorage.getItem("lightdata.web.v1")).layouts.l,
    ),
  ).toBeTruthy();
  await settings();
  await page.evaluate(() => {
    globalThis.testCameraSettings = {
      exposureMode: "manual",
      exposureTime: 1000,
      iso: 100,
    };
  });
  await page.waitForTimeout(600);
  await page.locator("#capture-mode").selectOption("reference");
  await page.locator("#capture-aperture").fill("2");
  await page.locator("#capture-iso").fill("100");
  await page.locator("#capture-time").fill("0.1");
  await page.locator("#capture-confirmed").check();
  await page.locator("#reference-ev").fill("8");
  await page.locator("#set-reference").click();
  await expect.poll(valueReading).toBeGreaterThan(7.5);
  await expect.poll(valueReading).toBeLessThan(8.5);
  await page.evaluate(() => {
    globalThis.testCameraSettings.exposureTime = 2000;
  });
  await expect(page.locator("#meter-reading")).toContainText("相对亮度");
  await page.locator("#capture-mode").selectOption("auto");
  await close();
  await expect(page.locator("#meter-reading")).toContainText("EV100");
  const pausedReading = await valueReading();
  await page.evaluate(() => {
    Object.defineProperty(document, "hidden", {
      configurable: true,
      value: true,
    });
    document.dispatchEvent(new Event("visibilitychange"));
    globalThis.testCameraSettings.exposureTime = 100;
  });
  await page.waitForTimeout(600);
  expect(await valueReading()).toBe(pausedReading);
  await page.evaluate(() => {
    Object.defineProperty(document, "hidden", {
      configurable: true,
      value: false,
    });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  await expect.poll(valueReading).toBeGreaterThan(pausedReading + 3);
  await settings();
  await page.locator("#camera-stop").click();
  await expect(page.locator("#live-state")).toContainText("OFF");
  await close();
  const retainedFixture = await page.evaluate(() => {
    const value = JSON.parse(localStorage.getItem("lightdata.web.v1"));
    const key = Object.keys(value.devices)[0];
    // This synthetic calibration checks retention only, not measurement accuracy.
    value.devices[key].calibrations = {
      100: {
        iso: 100,
        stops: 12,
        channel: "browser fixture",
        response: Array.from({ length: 256 }, (_, i) => (i - 117) / 20),
      },
    };
    Object.assign(value.devices[key], {
      mode: "manual",
      iso: 100,
      seconds: 0.1,
      confirmed: true,
    });
    Object.assign(value, {
      mode: "M",
      focal: 17,
      compensation: 2,
      style: "purple",
      preview: true,
      isoLocked: false,
      log: false,
    });
    localStorage.setItem("lightdata.web.v1", JSON.stringify(value));
    return {
      key,
      lenses: value.lenses,
      presets: value.presets,
      calibration: value.devices[key].calibrations,
    };
  });
  await page.reload();
  await settings();
  await expect(page.locator("#log-counts")).not.toBeChecked();
  await expect(page.locator("#color-style")).toHaveValue("purple");
  await page.locator("#settings-reset").scrollIntoViewIfNeeded();
  await page.screenshot({ path: path.join(snapshots, "settings-reset.png") });
  await Promise.all([
    page.waitForEvent("load"),
    page.locator("#settings-reset").click(),
  ]);
  await expect(page.locator("#format option")).toHaveCount(17);
  await expect(page.locator("#format")).toHaveValue("4");
  await expect(page.locator("#focal")).toHaveValue("35");
  await expect(page.locator("[data-mode=A]")).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  await expect(page.locator("#preview-toggle")).toHaveAttribute(
    "aria-pressed",
    "false",
  );
  await expect(page.locator("#comp")).toHaveValue("0");
  await expect(page.locator("#pixel-label")).toHaveText("像素 log");
  await expect(page.locator("#camera-setup")).toBeHidden();
  await settings();
  await expect(page.locator("#log-counts")).toBeChecked();
  await expect(page.locator("#color-style")).toHaveValue("color");
  await expect(page.locator("#custom-iso")).toHaveValue("400");
  await expect(page.locator("#custom-iso")).toBeDisabled();
  await expect(page.locator("#film-select option:checked")).toHaveText(
    "HP5 PLUS · ISO 400",
  );
  const afterReset = await page.evaluate(() =>
    JSON.parse(localStorage.getItem("lightdata.web.v1")),
  );
  expect(afterReset.lenses).toEqual(retainedFixture.lenses);
  expect(afterReset.presets).toBe(retainedFixture.presets);
  expect(afterReset.devices[retainedFixture.key].calibrations).toEqual(
    retainedFixture.calibration,
  );
  expect(afterReset.devices[retainedFixture.key].mode).toBe("auto");
  expect(afterReset.devices[retainedFixture.key].confirmed).toBe(false);
  expect(afterReset.layouts || {}).toEqual({});
  expect(
    await page.evaluate(() => localStorage.getItem("lightdata.test-unrelated")),
  ).toBe("keep");
  await page.locator("#log-counts").uncheck();
  await close();
  await page.reload();
  await settings();
  await expect(page.locator("#log-counts")).not.toBeChecked();
  await expect(page.locator("#pixel-label")).toHaveText("像素");
  await close();
  const phoneContext = await browser.newContext({
    locale: "zh-CN",
    permissions: ["camera"],
    viewport: { width: 430, height: 932 },
  });
  await phoneContext.addInitScript(() => {
    Object.defineProperty(navigator, "userAgentData", {
      configurable: true,
      value: {
        getHighEntropyValues: async () => ({
          model: "SM-F9660",
          platform: "Android",
        }),
      },
    });
    const original = MediaStreamTrack.prototype.getSettings;
    MediaStreamTrack.prototype.getSettings = function () {
      return {
        ...original.call(this),
        deviceId: "matched-phone-camera",
        exposureTime: 100,
        iso: 250,
        facingMode: "environment",
      };
    };
  });
  const phone = await phoneContext.newPage();
  phone.on("pageerror", (error) => errors.push(error.message));
  await phone.goto(base);
  await expect(phone.locator("#camera-setup")).toBeVisible();
  await expect(phone.locator("#pixel-label")).toHaveText("像素 log");
  await expect(phone.locator("#setup-brand")).toHaveValue("Samsung");
  await expect(phone.locator("#setup-phone")).toHaveValue("samsung-fold7");
  await expect(phone.locator('#setup-phone option[value^="apple-"]')).toHaveCount(0);
  await expect(phone.locator("#setup-detection")).toContainText(
    "UA Client Hints",
  );
  await expect(phone.locator("#setup-lens")).toHaveValue("");
  await phone.locator("#setup-start").click();
  await expect(phone.locator("#live-state")).toContainText("LIVE");
  await expect(phone.locator("#setup-camera option")).toHaveCount(1);
  await expect(phone.locator("#setup-save")).toHaveText("保存并使用");
  await phone.locator("#setup-lens").selectOption("wide");
  await expect(phone.locator("#setup-aperture")).toHaveValue("1.7");
  await expect(phone.locator("#setup-hfov")).toHaveValue("");
  await expect(phone.locator("#setup-equivalent")).toHaveValue("24");
  await expect(phone.locator("#setup-fov-mode")).toHaveValue("equivalent");
  await expect(phone.locator("#setup-source")).toHaveAttribute(
    "href",
    /samsung\.com/,
  );
  await expect(phone.locator("#setup-equivalent-source")).toHaveAttribute(
    "href", /notebookcheck\.net/,
  );
  await phone.locator("#setup-brand").selectOption("Apple");
  await expect(phone.locator("#setup-phone")).toHaveValue("");
  await expect(phone.locator("#setup-phone")).toBeEnabled();
  await expect(phone.locator("#setup-lens")).toBeDisabled();
  await expect(phone.locator("#setup-aperture")).toHaveValue("");
  await expect(phone.locator("#setup-equivalent")).toHaveValue("");
  await expect(phone.locator('#setup-phone option[value^="samsung-"]')).toHaveCount(0);
  await expect(phone.locator('#setup-phone option[value="apple-iphone-x"]')).toHaveText("iPhone X");
  await expect(phone.locator('#setup-phone option[value="apple-iphone-xs-max"]')).toHaveText("iPhone XS Max");
  await expect(phone.locator('#setup-phone option[value="apple-iphone-13-mini"]')).toHaveText("iPhone 13 mini");
  await phone.locator("#setup-phone").selectOption("apple-iphone-x");
  await phone.locator("#setup-lens").selectOption("wide");
  await expect(phone.locator("#setup-aperture")).toHaveValue("1.8");
  await phone.locator("#setup-phone").selectOption("apple-iphone-15-pro-max");
  await expect(phone.locator("#setup-aperture")).toHaveValue("");
  await phone.locator("#setup-lens").selectOption("telephoto");
  await expect(phone.locator("#setup-equivalent")).toHaveValue("120");
  await phone.locator("#setup-phone").selectOption("apple-iphone-15-pro");
  await phone.locator("#setup-lens").selectOption("telephoto");
  await expect(phone.locator("#setup-equivalent")).toHaveValue("77");
  await phone.screenshot({ path: path.join(snapshots, "lens-brand-iphone.png") });
  await phone.locator("#setup-brand").selectOption("Xiaomi");
  await expect(phone.locator("#setup-phone")).toHaveValue("");
  await expect(phone.locator("#setup-lens")).toBeDisabled();
  await expect(phone.locator("#setup-aperture")).toHaveValue("");
  await expect(phone.locator("#setup-equivalent")).toHaveValue("");
  await phone.locator("#setup-phone").selectOption("xiaomi-15");
  await expect(phone.locator("#setup-aperture")).toHaveValue("");
  await phone.locator("#setup-lens").selectOption("wide");
  await expect(phone.locator("#setup-aperture")).toHaveValue("1.62");
  await expect(phone.locator("#setup-equivalent")).toHaveValue("23");
  await expect(phone.locator("#setup-fov-mode")).toHaveValue("equivalent");
  await phone.screenshot({
    path: path.join(snapshots, "lens-matched-phone.png"),
  });
  await phone.locator("#setup-save").click();
  await expect(phone.locator("#live-state")).toContainText("LIVE");
  await expect(phone.locator("#camera-setup")).toBeHidden();
  await expect(phone.locator("#meter-reading")).toContainText("EV100");
  await phone.locator("#settings-toggle").click();
  await expect(phone.locator("#capture-aperture")).toHaveValue("1.62");
  await expect(phone.locator("#camera-equivalent")).toHaveValue("23");
  await phone.locator("#configure-lens").click();
  await expect(phone.locator("#setup-brand")).toHaveValue("Xiaomi");
  await expect(phone.locator("#setup-phone")).toHaveValue("xiaomi-15");
  await expect(phone.locator("#setup-lens")).toHaveValue("wide");
  await expect(phone.locator("#setup-equivalent")).toHaveValue("23");
  await phone.locator("#setup-close").click();
  await expect(phone.locator("#settings")).toBeVisible();
  const lensProfiles = await phone.evaluate(
    () => JSON.parse(localStorage.getItem("lightdata.web.v1")).lenses,
  );
  expect(lensProfiles["matched-phone-camera"].phoneId).toBe("xiaomi-15");
  expect(lensProfiles["matched-phone-camera"].equivalentMm).toBe(23);
  await phone.reload();
  await phone.locator("#settings-toggle").click();
  await phone.locator("#configure-lens").click();
  await expect(phone.locator("#setup-brand")).toHaveValue("Xiaomi");
  await expect(phone.locator("#setup-phone")).toHaveValue("xiaomi-15");
  await phone.locator("#setup-brand").selectOption("");
  await expect(phone.locator("#setup-phone")).toHaveValue("");
  await expect(phone.locator("#setup-phone")).toBeDisabled();
  await expect(phone.locator("#setup-lens")).toBeDisabled();
  await expect(phone.locator("#setup-aperture")).toHaveValue("");
  await phoneContext.close();
  const denied = await browser.newContext({
    locale: "zh-CN",
    permissions: [],
    viewport: { width: 430, height: 932 },
  });
  await denied.addInitScript(() => {
    navigator.mediaDevices.getUserMedia = async () => {
      throw new DOMException("Denied", "NotAllowedError");
    };
  });
  const d = await denied.newPage();
  await d.goto(base);
  await expect(d.locator("#camera-setup")).toBeVisible();
  await d.locator("#setup-aperture").fill("2");
  await d.locator("#setup-hfov").fill("70");
  await d.locator("#setup-save").click();
  await expect(d.locator("#start-message")).toContainText("权限被拒绝");
  await expect(d.locator("#setup-error")).toContainText("相机权限被拒绝");
  await denied.close();
  // A separate storage context verifies browser-language selection and saved overrides.
  const englishContext = await browser.newContext({
    locale: "en-US",
    viewport: { width: 430, height: 932 },
    permissions: ["camera"],
  });
  await englishContext.addInitScript(() => {
    const original = MediaStreamTrack.prototype.getSettings;
    MediaStreamTrack.prototype.getSettings = function () {
      return {
        ...original.call(this),
        deviceId: "english-fixture-camera",
        exposureMode: "continuous",
        exposureTime: 299.94234,
        iso: 250,
      };
    };
  });
  const english = await englishContext.newPage();
  english.on("pageerror", (error) => errors.push(error.message));
  await english.goto(base);
  await expect(english.locator("html")).toHaveAttribute("lang", "en");
  await expect(english).toHaveTitle("LightData · Viewfinder & Light Meter");
  await expect(english.locator("#camera-setup")).toBeVisible();
  await expect(english.locator("#camera-setup h2")).toHaveText("Set up phone lens");
  await english.locator("#setup-save").click();
  await expect(english.locator("#setup-error")).toContainText("Enter an aperture");
  await english.locator("#setup-aperture").fill("2.3");
  await english.locator("#setup-hfov").fill("70");
  await expect(english.locator("#setup-field-info")).toContainText("horizontal 70°");
  await english.locator("#setup-save").click();
  await expect(english.locator("#camera-setup")).toBeHidden();
  await expect(english.locator("#meter-reading")).toContainText("EV100 · Estimated");
  await expect(english.locator("#chart")).toHaveAttribute("aria-label", /Histogram horizontal axis/);
  await expect(english.locator("#thresholds")).toContainText("Low");
  await english.locator("#focal").fill("42");
  await english.locator("#focal").dispatchEvent("change");
  await english.locator("#settings-toggle").click();
  await expect(english.locator("#capture-info")).toContainText("Used for scene EV100");
  await expect(english.locator("#language-select")).toHaveValue("en");
  await english.locator("#preview-mode").selectOption("lut");
  await expect(english.locator("#lut-info")).toContainText("exposure warnings");
  await expect(english.locator("#lut-variant option").first()).not.toContainText(/[\u3400-\u9fff]/);
  await expect(english.locator("#configure-lens")).toHaveCSS("background-color", "rgb(255, 189, 89)");
  await expect(english.locator("#configure-lens")).toHaveCSS("color", "rgb(16, 17, 18)");
  await english.locator("#configure-lens").click();
  await expect(english.locator("#camera-setup")).toBeVisible();
  await expect(english.locator("#setup-aperture")).toHaveValue("2.3");
  await english.locator("#setup-close").click();
  await expect(english.locator("#settings")).toBeVisible();
  await english.locator("[data-close=settings]").click();
  await english.locator("#more-toggle").click();
  await expect(english.locator("#more-title")).toHaveText("More");
  await expect(english.locator("#loss-info")).toHaveText("Total light loss +0 EV");
  await english.locator("#ground-tools summary").click();
  await expect(english.locator("#ground-profile")).toContainText("no ground glass calibration");
  await english.setViewportSize({ width: 320, height: 700 });
  await expect.poll(() => english.locator("#more").evaluate((dialog) => dialog.scrollWidth <= dialog.clientWidth)).toBe(true);
  await english.screenshot({ path: path.join(snapshots, "more-english-320.png") });
  await english.locator("[data-close=more]").click();
  await english.setViewportSize({ width: 430, height: 932 });
  const untranslated = await english.evaluate(() => {
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    const remaining = [];
    while (walker.nextNode()) {
      const node = walker.currentNode;
      if (
        !node.parentElement.closest('script,style,[translate="no"],#track-info') &&
        /[\u3400-\u9fff]/.test(node.nodeValue) &&
        // Brand names and the native language option deliberately retain their spelling.
        !node.nodeValue.includes("乐凯")
      ) remaining.push(node.nodeValue.trim());
    }
    return remaining;
  });
  expect(untranslated).toEqual([]);
  for (const [width, height] of [[430, 932], [768, 1024], [1366, 768]]) {
    await english.setViewportSize({ width, height });
    await expect.poll(() => english.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await english.screenshot({ path: path.join(snapshots, `english-${width}.png`) });
  }
  const beforeLanguage = await english.evaluate(() => JSON.parse(localStorage.getItem("lightdata.web.v1")));
  await english.locator("#settings-toggle").click();
  await english.locator("#language-select").selectOption("zh-CN");
  await expect(english.locator("html")).toHaveAttribute("lang", "zh-CN");
  await expect(english.locator("#settings-toggle")).toContainText("设置");
  await expect(english.locator("#focal")).toHaveValue("42");
  await english.locator("#settings-toggle").click();
  await expect(english.locator("#language-select")).toHaveValue("zh-CN");
  await english.locator("#language-select").selectOption("en");
  await expect(english.locator("html")).toHaveAttribute("lang", "en");
  await expect(english.locator("#focal")).toHaveValue("42");
  const afterLanguage = await english.evaluate(() => ({
    preferences: JSON.parse(localStorage.getItem("lightdata.web.v1")),
    language: localStorage.getItem("lightdata.language"),
  }));
  expect(afterLanguage.language).toBe("en");
  for (const key of ["focal", "manual", "filmId", "lenses", "devices"]) {
    expect(afterLanguage.preferences[key]).toEqual(beforeLanguage[key]);
  }
  await englishContext.close();
  expect(errors).toEqual([]);
  console.log(
    "Browser checks passed: lens setup, optics/presets/calibration persistence, brand/model selection, all iPhones from X onward, bilingual UI, continuous/single and relative metering, ISO lock, zone placement, filter/bellows and ground glass compensation, framing overlays, level, confirmation sound and credit, low light and reciprocity source, histogram gestures, panoramas, four layouts, background pause/resume and permission denial.",
  );
  console.log("Screenshots:", snapshots);
} catch (error) {
  await page.screenshot({ path: path.join(snapshots, "failure.png") });
  console.log("Page errors:", errors);
  console.log(
    "Capture profile diagnostic:",
    await page.evaluate(() => ({
      current: document
        .getElementById("capture-video")
        .srcObject?.getVideoTracks()[0]
        ?.getSettings(),
      saved: Object.entries(
        JSON.parse(localStorage.getItem("lightdata.web.v1") || "{}").devices ||
          {},
      ).map(([key, p]) => ({ key, aperture: p.aperture, mode: p.mode })),
    })),
  );
  throw error;
} finally {
  await browser.close();
  server.close();
  await fs.unlink(fixtureFile);
  await fs.rmdir(fixtureDirectory);
}

import { onlineReciprocity } from "./online-reciprocity.js";

// Numerical parity with the Android application. Values are linear-light unless named code.
export const FORMATS = [
  ["1 英寸", 13.2, 8.8],
  ["M43", 17.3, 13],
  ["APS-C", 23.5, 15.6],
  ["APS-C Canon", 22.3, 14.9],
  ["全幅", 36, 24],
  ["44×33", 44, 33],
  ["48×36", 48, 36],
  ["54×40", 54, 40],
  ["135", 36, 24],
  ["645", 56, 41.5],
  ["6×6", 56, 56],
  ["6×7", 69, 56],
  ["6×9", 84, 56],
  ["6×12", 112, 56],
  ["6×17", 168, 56],
  ["4×5", 120, 96],
  ["8×10", 245, 194],
].map(([name, w, h]) => ({ name, w, h }));

export const APERTURES = [
  0.7, 0.8, 0.9, 1, 1.1, 1.2, 1.4, 1.6, 1.8, 2, 2.2, 2.5, 2.8, 3.2, 3.5, 4, 4.5,
  5, 5.6, 6.3, 7.1, 8, 9, 10, 11, 13, 14, 16, 18, 20, 22, 25, 29, 32, 36, 40,
  45, 51, 57, 64, 72, 81, 90,
];
export const SHUTTERS = [
  8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500,
  400, 320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8,
  6, 5, 4, 3,
]
  .map((v) => 1 / v)
  .concat([
    0.4, 0.5, 0.6, 0.8, 1, 1.3, 1.6, 2, 2.5, 3.2, 4, 5, 6, 8, 10, 13, 15, 20,
    25, 30, 40, 50, 60, 120, 240,
  ]);
export const ISOS = [
  3, 4, 5, 6, 8, 10, 12, 16, 20, 25, 32, 40, 50, 64, 80, 100, 125, 160, 200,
  250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000,
  6400, 12800, 25600,
];
export const clamp = (v, low, high) => Math.max(low, Math.min(high, v));
export const srgbToLinear = (v) =>
  v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
export const linearToSrgb = (v) =>
  v <= 0.0031308 ? 12.92 * v : 1.055 * v ** (1 / 2.4) - 0.055;
const toLinear = Float64Array.from({ length: 256 }, (_, i) =>
  srgbToLinear(i / 255),
);
const linear16 = Uint32Array.from(toLinear, (v) => Math.round(v * 65535));
const grayCode = Uint8Array.from({ length: 65536 }, (_, i) =>
  Math.round(255 * linearToSrgb(i / 65535)),
);
export const defaultResponse = Float64Array.from({ length: 256 }, (_, i) =>
  Math.log2(srgbToLinear(Math.max(0.5, i) / 255) / 0.18),
);
export const luma = (r, g, b) =>
  grayCode[
    Math.round(
      linear16[r] * 0.2126 + linear16[g] * 0.7152 + linear16[b] * 0.0722,
    )
  ];
export const scaleSrgbCode = (code, gain) =>
  grayCode[clamp(Math.round(linear16[code] * gain), 0, 65535)];
export const mm = (value, inch) => value * (inch ? 25.4 : 1);
export const field = (w, h, focal) => ({
  x: w / (2 * focal),
  y: h / (2 * focal),
  aspect: w / h,
});
export const exposureEV = ({ t, a, iso }) =>
  Math.log2((((a * a) / t) * 100) / iso);
export const timeLabel = (t) =>
  t < 0.4 ? "1/" + Math.round(1 / t) : Number(t.toFixed(2)) + "″";
export const closest = (values, x) =>
  values.reduce((a, b) =>
    Math.abs(Math.log2(a / x)) <= Math.abs(Math.log2(b / x)) ? a : b,
  );

export function solve(mode, manual, sceneEv, compensationEV = 0) {
  if (mode === "M") return { ...manual };
  if (!["A", "S"].includes(mode)) throw new Error("未知曝光档位");
  const light =
    (2 ** (sceneEv - clamp(compensationEV, -5, 5)) * manual.iso) / 100;
  return mode === "S"
    ? { ...manual, a: closest(APERTURES, Math.sqrt(light * manual.t)) }
    : { ...manual, t: closest(SHUTTERS, (manual.a * manual.a) / light) };
}

export function equivalentExposures(center, { exactLong = false } = {}) {
  const groups = new Map();
  for (const a of APERTURES) {
    const stop = 2 * Math.log2(a / center.a),
      step = Math.round(stop);
    if (
      !groups.has(step) ||
      Math.abs(stop - step) <
        Math.abs(2 * Math.log2(groups.get(step) / center.a) - step)
    )
      groups.set(step, a);
  }
  const pairs = [center];
  for (const [step, a] of groups) {
    if (!step || Math.abs(2 * Math.log2(a / center.a) - step) > 0.18) continue;
    const ideal = center.t * (a / center.a) ** 2,
      t = closest(SHUTTERS, ideal);
    if (Math.abs(Math.log2(t / ideal)) <= 0.18) pairs.push({ ...center, a, t });
    else if (exactLong && Number.isFinite(ideal) && ideal > 30) pairs.push({ ...center, a, t: ideal });
  }
  return pairs.sort((a, b) => a.a - b.a);
}

export function histogram(imageData) {
  const hist = new Uint32Array(256),
    data = imageData.data;
  for (let i = 0; i < data.length; i += 4)
    if (data[i + 3]) hist[luma(data[i], data[i + 1], data[i + 2])]++;
  return hist;
}

export function percentile(hist, q) {
  const target = Math.max(1, Math.round(hist.reduce((a, b) => a + b, 0) * q));
  let count = 0;
  for (let i = 0; i < hist.length; i++)
    if ((count += hist[i]) >= target) return i;
  return hist.length - 1;
}

export function meter(hist, response = defaultResponse) {
  const lo = percentile(hist, 0.02),
    hi = percentile(hist, 0.98);
  let sum = 0,
    count = 0;
  for (let i = 0; i < hist.length; i++) {
    sum += hist[i] * 2 ** response[clamp(i, lo, hi)];
    count += hist[i];
  }
  return count ? Math.log2(Math.max(1e-8, sum / count)) : NaN;
}

export function spot(
  imageData,
  x,
  y,
  response = defaultResponse,
  cameraEv = 0,
) {
  const { width, height, data } = imageData;
  const center = (clamp(Math.floor(y * height), 0, height - 1) * width +
    clamp(Math.floor(x * width), 0, width - 1)) * 4;
  if (!data[center + 3]) return NaN;
  let sum = 0,
    count = 0;
  for (let dy = -2; dy <= 2; dy++)
    for (let dx = -2; dx <= 2; dx++) {
      const i =
        (clamp(Math.floor(y * height) + dy, 0, height - 1) * width +
          clamp(Math.floor(x * width) + dx, 0, width - 1)) *
        4;
      if (data[i + 3]) {
        sum += 2 ** response[luma(data[i], data[i + 1], data[i + 2])];
        count++;
      }
    }
  return count ? cameraEv + Math.log2(sum / count) : NaN;
}

export function interpolate(points, x) {
  let lo = 0,
    hi = points.length - 1;
  if (x <= points[0][0]) return points[0][1];
  if (x >= points[hi][0]) return points[hi][1];
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1;
    if (points[mid][0] < x) lo = mid;
    else hi = mid;
  }
  return (
    points[lo][1] +
    ((points[hi][1] - points[lo][1]) * (x - points[lo][0])) /
      (points[hi][0] - points[lo][0])
  );
}
export const density = (film, ev) => interpolate(film.dots, ev);
export function positive(film, ev) {
  if (film.digital)
    return clamp(0.18 * 2 ** clamp(ev, film.low, film.high), 0, 1);
  const direction = film.dots.at(-1)[1] > film.dots[0][1] ? 1 : -1;
  if (direction < 0)
    return clamp(0.18 * 10 ** (density(film, 0) - density(film, ev)), 0, 1);
  return layerPositive(film.dots, ev);
}

export async function loadFilms(base = "./data/") {
  const response = await fetch(base + "films.json");
  if (!response.ok) throw new Error("胶片曲线载入失败，请刷新重试");
  const films = await response.json();
  for (const film of films)
    if (film.color) film.color.matrix = spectralMatrix(film.color.spectral);
  catalogue = films;
  return films;
}
let catalogue = [];
export function spectralMatrix(spectral) {
  const centers = [610, 545, 450],
    widths = [45, 35, 30];
  return ["red", "green", "blue"].map((layer, index) => {
    const weights = centers.map((center, c) =>
      (spectral[layer] || []).reduce(
        (s, [nm, log]) =>
          s + 10 ** log * Math.exp(-0.5 * ((nm - center) / widths[c]) ** 2),
        0,
      ),
    );
    const total = weights.reduce((s, w) => s + w, 0);
    return total
      ? weights.map((w) => w / total)
      : centers.map((_, i) => (i === index ? 1 : 0));
  });
}

// Cache the print contrast per immutable datasheet curve. The 0.82 factor
// compensates for the shoulder's slope at the 18% gray anchor.
const printCurves = new WeakMap();
export function layerPositive(points, ev) {
  let curve = printCurves.get(points);
  if (!curve) {
    const span = Math.max(0.01, interpolate(points, 1) - interpolate(points, -1));
    curve = { gray: interpolate(points, 0),
      stopsPerDensity: clamp(2 / (0.82 * span), Math.log2(10), 8 * Math.log2(10)) };
    printCurves.set(points, curve);
  }
  const stops = (interpolate(points, ev) - curve.gray) * curve.stopsPerDensity;
  return 1 / (1 + (0.82 / 0.18) * 2 ** -stops);
}

// Existing 16-bit linear-to-sRGB table; round only at the output precision.
function encodedCode(linear) {
  return grayCode[Math.round(clamp(linear, 0, 1) * 65535)];
}

// Layer response tables are indexed by an 8-bit code. Check the exact code
// boundaries so a rounded 16-bit input cannot select the adjacent film sample.
const codeBoundaries = Float64Array.from({ length: 256 }, (_, i) => srgbToLinear((i + .5) / 255));
function layerCode(linear) {
  const code = encodedCode(linear);
  if (code > 0 && linear < codeBoundaries[code - 1]) return code - 1;
  if (code < 255 && linear >= codeBoundaries[code]) return code + 1;
  return code;
}

/** A display transform. Measurement must always use the unmodified source pixels. */
export function renderImage(
  imageData,
  {
    film,
    style = "color",
    preview = false,
    manual = false,
    cameraEv = 0,
    targetEv = 0,
    response = defaultResponse,
    stripes = true,
  } = {},
) {
  const { data, width, height } = imageData,
    out = new Uint8ClampedArray(data.length);
  const delta = cameraEv - targetEv,
    tone = preview || manual;
  const targets = new Float64Array(256),
    limits = new Int8Array(256);
  const color =
    preview &&
    style === "color" &&
    ["red", "green", "blue"].every(
      (channel) => film?.color?.density?.[channel]?.length >= 2,
    )
      ? film.color.density
      : null;
  const matrix = color
    ? film.color.matrix || spectralMatrix(film.color.spectral)
    : null;
  const layers = color
    ? ["red", "green", "blue"].map((channel) =>
        Float64Array.from({ length: 256 }, (_, i) =>
          linearToSrgb(layerPositive(color[channel], response[i] + delta)),
        ),
      )
    : null;
  for (let i = 0; i < 256; i++) {
    const ev = response[i] + delta;
    targets[i] =
      preview && film
        ? positive(film, ev)
        : manual
          ? clamp(0.18 * 2 ** ev, 0, 1)
          : toLinear[i];
    if (film) limits[i] = ev < film.low ? -1 : ev > film.high ? 1 : 0;
  }
  for (let i = 0; i < data.length; i += 4) {
    if (!data[i + 3]) continue;
    const gray = luma(data[i], data[i + 1], data[i + 2]);
    let r = data[i] / 255,
      g = data[i + 1] / 255,
      b = data[i + 2] / 255;
    if (style === "bw") r = g = b = gray / 255;
    else if (style === "aero")
      [r, g, b] = [Math.max(g, r * 0.65), r * 0.8, b * 0.72];
    else if (style === "purple")
      [r, g, b] = [Math.max(r, g * 0.86), b * 0.72, Math.max(b, g * 0.93)];
    if (layers) {
      const x = toLinear[data[i]], y = toLinear[data[i + 1]], z = toLinear[data[i + 2]];
      const rowR = matrix[0], rowG = matrix[1], rowB = matrix[2];
      r = layers[0][layerCode(rowR[0] * x + rowR[1] * y + rowR[2] * z)];
      g = layers[1][layerCode(rowG[0] * x + rowG[1] * y + rowG[2] * z)];
      b = layers[2][layerCode(rowB[0] * x + rowB[1] * y + rowB[2] * z)];
    } else if (tone) {
      const plain = style === "color";
      const lr = plain ? toLinear[data[i]] : srgbToLinear(r);
      const lg = plain ? toLinear[data[i + 1]] : srgbToLinear(g);
      const lb = plain ? toLinear[data[i + 2]] : srgbToLinear(b);
      const y = lr * .2126 + lg * .7152 + lb * .0722, target = targets[gray];
      if (y > 1e-6) {
        const gain = target / y;
        r = encodedCode(lr * gain) / 255;
        g = encodedCode(lg * gain) / 255;
        b = encodedCode(lb * gain) / 255;
      } else r = g = b = encodedCode(target) / 255;
    }
    if (stripes && limits[gray]) {
      const x = (i / 4) % width,
        y = Math.floor(i / 4 / width);
      const marker = limits[gray] > 0 ? [1, 0.22, 0.2] : [0.12, 0.86, 0.52];
      const phase = limits[gray] > 0 ? x + y : x - y;
      if (((phase % 12) + 12) % 12 < 3)
        [r, g, b] = [r, g, b].map((c, j) => c * 0.18 + marker[j] * 0.82);
    }
    out[i] = Math.round(r * 255);
    out[i + 1] = Math.round(g * 255);
    out[i + 2] = Math.round(b * 255);
    out[i + 3] = data[i + 3];
  }
  return typeof ImageData === "undefined"
    ? { data: out, width, height }
    : new ImageData(out, width, height);
}

export function customFilm(preset) {
  validatePreset(preset);
  const { id, name, iso, digital, total, above } = preset,
    low = above - total;
  return {
    id,
    name: "自定义 · " + name,
    brand: "自定义",
    iso,
    digital,
    low,
    high: above,
    url: "",
    info: "按输入动态范围生成近似响应。",
    dots: Array.from({ length: 33 }, (_, i) => {
      const x = i / 32;
      return [low + total * x, digital ? 2.6 * x : 2.6 * x * x * (3 - 2 * x)];
    }),
  };
}

function validatePreset(p) {
  const expected = ["id", "name", "digital", "total", "above", "iso"];
  if (
    !p ||
    typeof p !== "object" ||
    Object.keys(p).length !== expected.length ||
    !expected.every((key) => Object.hasOwn(p, key))
  )
    throw new Error("预设字段不完整或含未知字段");
  if (
    typeof p.id !== "string" ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(p.id)
  )
    throw new Error("预设 ID 必须是小写 UUID");
  if (
    typeof p.name !== "string" ||
    !p.name.length ||
    p.name.length > 80 ||
    p.name !== p.name.trim() ||
    /[\x00-\x1f\x7f-\x9f]/.test(p.name)
  )
    throw new Error("预设名称须为 1–80 个字符");
  if (typeof p.digital !== "boolean") throw new Error("digital 须为布尔值");
  if (typeof p.total !== "number" || !(p.total >= 1 && p.total <= 32))
    throw new Error("总动态范围须在 1–32 EV 内");
  if (
    typeof p.above !== "number" ||
    !(p.above >= 0.1 && p.above <= p.total - 0.1)
  )
    throw new Error("中灰两侧均须至少保留 0.1 EV");
  if (typeof p.iso !== "number" || !(p.iso >= 0.001 && p.iso <= 10000000))
    throw new Error("参考 ISO 超出范围");
  return p;
}
function checkPresets(presets) {
  if (!Array.isArray(presets) || presets.length > 2048)
    throw new Error("预设须为数组，最多 2048 个");
  presets.forEach(validatePreset);
  if (new Set(presets.map((p) => p.id)).size !== presets.length)
    throw new Error("文件中出现重复预设 ID");
  return presets;
}
function rejectDuplicateKeys(text) {
  const stack = [];
  const tokens = /"(?:\\.|[^"\\])*"|[{}\[\]]/g;
  for (const token of text.matchAll(tokens)) {
    const t = token[0];
    if (t === "{") stack.push(new Set());
    else if (t === "[") stack.push(null);
    else if (t === "}" || t === "]") stack.pop();
    else {
      let i = token.index + t.length;
      while (/\s/.test(text[i] || "x")) i++;
      if (text[i] !== ":" || !stack.at(-1)) continue;
      const key = JSON.parse(t),
        fields = stack.at(-1);
      if (fields.has(key)) throw new Error("重复字段 " + key);
      fields.add(key);
    }
  }
}
export const CustomPresets = {
  encode(presets) {
    return JSON.stringify(
      {
        format: "lightdata.custom-presets",
        version: 1,
        presets: checkPresets(presets),
      },
      null,
      2,
    );
  },
  decode(text) {
    if (
      typeof text !== "string" ||
      new TextEncoder().encode(text).length > 2 * 1024 * 1024
    )
      throw new Error("预设文件超过 2 MiB");
    const value = JSON.parse(text);
    rejectDuplicateKeys(text);
    if (
      !value ||
      Object.keys(value).length !== 3 ||
      value.format !== "lightdata.custom-presets" ||
      value.version !== 1
    )
      throw new Error("无法识别预设格式或版本");
    return checkPresets(value.presets);
  },
  merge(existing, incoming) {
    const byId = new Map(checkPresets(existing).map((p) => [p.id, p]));
    checkPresets(incoming).forEach((p) => byId.set(p.id, p));
    return checkPresets([...byId.values()]);
  },
};

const ILFORD =
  "https://www.ilfordphoto.com/wp/wp-content/uploads/2024/05/Reciprocity-Failure-Compensation-v2.pdf";
const PRO_160S = "https://asset.fujifilm.com/www/us/files/2020-03/d52487c5c6f84e7f935c299491c5c1ff/ProfessionalFilmDataGuide.pdf#page=16";
const VELVIA_100 = "https://asset.fujifilm.com/master/emea/files/2020-10/2f3c7f90a0b0c6e605e84f98b7d489c2/films_velvia-100_datasheet_01.pdf#page=2";
const powers = {
  "SFX 200": 1.43,
  "PAN F PLUS": 1.33,
  "DELTA 100": 1.26,
  "DELTA 400": 1.41,
  "DELTA 3200": 1.33,
  "FP4 PLUS": 1.26,
  "HP5 PLUS": 1.31,
  "XP2 SUPER": 1.31,
  "ORTHO PLUS": 1.25,
  "PAN 100 · Kentmere": 1.26,
  "PAN 400 · Kentmere": 1.3,
};
const ranges = new Map();
function range(names, min, max) {
  names.forEach((name) => ranges.set(name, [min, max]));
}
range(
  [
    "PORTRA 160",
    "PORTRA 400",
    "PORTRA 800",
    "PORTRA 160NC",
    "PORTRA 160VC",
    "PORTRA 400NC",
    "PORTRA 400VC",
    "EKTAR 100",
    "GOLD 200",
    "ULTRAMAX 400",
    "ULTRA MAX 800",
  ],
  0.0001,
  1,
);
range(
  ["VISION3 50D", "VISION3 200T", "VISION3 250D", "VISION3 500T"],
  0.001,
  1,
);
range(
  ["FUJICOLOR C200", "PRO 160S", "SUPERIA X-TRA 400", "SUPERIA 1600"],
  0.00025,
  2,
);
range(["VELVIA 50", "PRO 400H", "SUPERIA REALA 100"], 0.00025, 1);
range(["VELVIA 100"], 0.00025, 60);
range(["PROVIA 100F"], 0.00025, 128);
range(["ASTIA 100"], 0.00025, 32);
range(["ASTIA 100F"], 0.00025, 60);
range(["EKTACHROME E100"], 0.0001, 10);
range(["T-MAX 400"], 0.0001, 1);
range(["T-MAX 100"], 0.001, 0.1);
range(["TRI-X 320", "TRI-X 400"], 0.01, 0.1);
range(
  [
    "FOMAPAN 100 Classic",
    "FOMAPAN 200 Creative",
    "FOMAPAN 400 Action",
    "RETROPAN 320 soft",
    "FOMA ORTHO 400",
  ],
  0.001,
  0.5,
);
const tables = {
  "FOMAPAN 100 Classic": { 1: 2, 10: 80, 100: 1600 },
  "FOMAPAN 200 Creative": { 1: 3, 10: 90, 100: 1800 },
  "FOMAPAN 400 Action": { 1: 1.5, 10: 60, 100: 800 },
  "RETROPAN 320 soft": { 1: 1.5, 10: 60, 100: 800 },
  "FOMA ORTHO 400": { 1: 1.5, 10: 60, 100: 800 },
  "T-MAX 100": { 10: 15, 100: 200 },
  "T-MAX 400": { 100: 300 },
  "TRI-X 400": { 1: 2, 10: 50, 100: 1200 },
  "TRI-X 320": { 1: 2, 10: 50, 100: 1200 },
};
const advice = {
  "T-MAX 100": { 0.0001: "开大 1/3 档", 1: "开大 1/3 档" },
  "T-MAX 400": { 10: "开大 1/3 档" },
  "PRO 400H": { 4: "开大 1/2 档", 16: "开大 1 档" },
  "PRO 160S": { 4: "开大 1/3 档" },
  "PROVIA 100F": { 240: "2.5G 滤镜，开大 1/3 档" },
  "VELVIA 50": {
    4: "5M 滤镜，开大 1/3 档",
    8: "7.5M 滤镜，开大 1/2 档",
    16: "10M 滤镜，开大 2/3 档",
    32: "12.5M 滤镜，开大 1 档",
  },
  "VELVIA 100": {
    120: "2.5M 滤镜，开大 1/3 档",
    240: "2.5M 滤镜，开大 1/2 档",
    480: "2.5M 滤镜，开大 2/3 档",
  },
  "PANCRO 400": { 1: "开大 1/2 档", 10: "开大 1 档", 60: "开大 2 档" },
  "EKTACHROME E100": { 120: "日光加 CC10R，快门补偿未给出" },
};

export function reciprocity(name, t) {
  const power =
    (Object.hasOwn(powers, name) ? powers[name] : null) ??
    (name === "Phoenix II"
      ? 1.31
      : name === "PAN 200 · Kentmere"
        ? 1.26
        : null);
  let source = Object.hasOwn(powers, name)
    ? ILFORD
    : name === "Phoenix II"
      ? "https://www.harmanphoto.co.uk/amfile/file/download/file/1967/product/2216/#page=2"
      : name === "PAN 200 · Kentmere"
        ? "https://www.ilfordphoto.com/wp/wp-content/uploads/2025/05/KENTMERE-PAN-200-260325.pdf"
        : name === "PRO 160S" ? PRO_160S
          : name === "VELVIA 100" ? VELVIA_100
            : catalogue.find((f) => f.name === name)?.url;
  const supported =
    power !== null || ranges.has(name) || Object.hasOwn(advice, name) || name === "ACROS II";
  if (!supported) source = null;
  const unknown = (note) => ({
    seconds: null,
    status: "unpublished",
    note,
    source: source || null,
  });
  const normal = () => ({
    seconds: t,
    status: "unchanged",
    note: "无需补偿",
    source,
  });
  const corrected = (seconds, extra = "", metadata = {}) =>
    Number.isFinite(seconds)
      ? {
          seconds,
          status: "corrected",
          note: "补偿后 " + Number(seconds.toFixed(1)) + " 秒" + extra,
          source,
          ...metadata,
        }
      : unknown("超出可计算范围");
  if (!Number.isFinite(t) || t <= 0) return unknown("快门时间无效");
  if (power !== null) {
    if (t < 0.0001) return unknown("厂家未给出补偿值");
    if (t <= 1) return normal();
    return corrected(t ** power, t > 50 ? " · 公式外推，建议试拍" : "", { extrapolated: t > 50 });
  }
  if (name === "ACROS II") {
    if (t < 120) return normal();
    if (t <= 1000) return unknown("开大 1/2 档，保留测得快门");
  }
  if (name === "PANCRO 400" && t < 1) return normal();
  const limits = ranges.get(name);
  if (limits && t >= limits[0] && t <= limits[1]) return normal();
  if (advice[name]?.[t]) return unknown(advice[name][t]);
  if (name === "PRO 160S" && t > 4) return unknown("厂家不建议此曝光时间");
  if (tables[name]?.[t])
    return corrected(
      tables[name][t],
      name.startsWith("TRI-X")
        ? " · 显影减 " + { 1: 10, 10: 20, 100: 30 }[t] + "%"
        : "",
    );
  const online = onlineReciprocity(name, t, Math.max(1, limits?.[1] ?? 1));
  if (online && Number.isFinite(online.seconds)) return corrected(online.seconds,
    " · 数据来源网络" + (online.extrapolated ? " · 公式外推" : ""), online);
  return unknown(supported ? "厂家未给此快门的补偿值" : "无此预设的补偿资料");
}

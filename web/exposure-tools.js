import { luma, percentile, solve } from "./core.js";

export function opticalLossStops(filterStops, drawMm, focalMm) {
  return filterStops + (drawMm > focalMm && focalMm > 0 ? 2 * Math.log2(drawMm / focalMm) : 0);
}

export function measuredTransmissionLoss(subjectEv, screenEv) {
  const loss = subjectEv - screenEv;
  return Number.isFinite(loss) && loss >= 0 ? loss : null;
}

export const zoneMiddleEv = (spotEv, zone) => spotEv - (zone - 5);

export function shootingExposure(mode, manual, middleEv, loss, compensation, placing) {
  const solveMode = placing && mode === "M" ? "A" : mode;
  const bias = placing ? 0 : compensation;
  const target = middleEv - loss;
  const exposure = solve(solveMode, manual, target, bias);
  if (solveMode === "A" && (loss > 0 || placing)) {
    const seconds = manual.a ** 2 * (100 / manual.iso) / 2 ** (target - bias);
    if (Number.isFinite(seconds) && seconds > 30 && Math.abs(Math.log2(seconds / exposure.t)) > .18)
      return { ...exposure, t: seconds };
  }
  return exposure;
}

export function dimMeterWarning(sceneEv, histogram) {
  return Number.isFinite(sceneEv) && !!histogram?.some(count => count > 0) &&
    (sceneEv <= 0 || (sceneEv <= 3 && percentile(histogram, .9) <= 16));
}

export function groundCenter(frame, base, response) {
  if (!frame || !Number.isFinite(base)) return null;
  const { width, height, data } = frame;
  if (width < 5 || height < 5) return null;
  const codes = [];
  for (let y = Math.floor(height / 2) - 2; y <= Math.floor(height / 2) + 2; y++) {
    for (let x = Math.floor(width / 2) - 2; x <= Math.floor(width / 2) + 2; x++) {
      const index = (y * width + x) * 4;
      if (!data[index + 3]) return null;
      codes.push(luma(data[index], data[index + 1], data[index + 2]));
    }
  }
  codes.sort((a, b) => a - b);
  if (codes[12] < 4 || codes[12] > 251) return null;
  return base + Math.log2(codes.reduce((sum, code) => sum + 2 ** response[code], 0) / codes.length);
}

export class GroundBurst {
  constructor(start) {
    this.timestamp = start;
    this.readings = [];
  }

  observe(time, ev) {
    if (!Number.isFinite(time) || time <= this.timestamp) return null;
    this.timestamp = time;
    if (!Number.isFinite(ev)) {
      this.readings.length = 0;
      return null;
    }
    this.readings.push(ev);
    if (this.readings.length < 5) return null;
    if (this.readings.length > 5) this.readings.shift();
    const sorted = [...this.readings].sort((a, b) => a - b);
    return sorted[4] - sorted[0] <= .4 ? sorted[2] : null;
  }
}

export function groundReading(screenEv, { offset, referenceAperture }, workingAperture, iso) {
  const adjusted = screenEv + offset;
  return {
    equivalentEv: adjusted + 2 * Math.log2(workingAperture / referenceAperture),
    seconds: referenceAperture ** 2 * (100 / iso) / 2 ** adjusted,
  };
}

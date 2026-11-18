const F8T = "https://f8t.app/reciprocity.html";
const METERGEIST = "https://metergeist.com/film/reciprocity-calculator";

const models = new Map([
  ["PORTRA 800", [F8T, 1.40, [[1, 2], [2, 4], [4, 10], [8, 22], [10, 30], [15, 50], [30, 130], [60, 320]]]],
  ["VELVIA 100", [F8T, 1.04, [[60, 60], [120, 152], [240, 340], [480, 780]]]],
  ["FUJICOLOR C200", [F8T, 1.25, [[2, 2], [4, 5], [8, 9], [16, 25], [30, 52], [60, 120], [120, 280]]]],
  ["FUJICOLOR 100", [F8T, 1.50]],
  ["NATURA 1600", [F8T, 1.45]],
  ["RPX 25", [F8T, 1.35, [[2, 3], [10, 21], [20, 54], [50, 181]]]],
  ["INFRARED 400", [F8T, 1.43]],
  ["RETRO 80S", [F8T, 1.49]],
  ["RETRO 400S", [F8T, 1.28]],
  ["SUPERPAN 200", [F8T, 1.20]],
  ["CHS 100 II", [F8T, 1.40, [[1, 1], [2, 3], [4, 8], [8, 20], [15, 45], [30, 120], [60, 390]]]],
  ["HR-50", [F8T, 1.20, [[.5, .6], [1, 1.3], [2, 2.4], [4, 5.6], [8, 13], [15, 28], [30, 75]]]],
  ["APX 100", [F8T, 1.53, [[1, 2], [2, 4], [4, 11], [8, 29], [10, 39], [20, 107], [30, 195], [40, 300], [50, 420], [60, 552], [100, 800]]]],
  ["Vista 200", [F8T, 1.25, [[1, 1], [2, 2], [4, 5], [8, 11], [16, 25], [32, 56], [64, 128]]]],
  ["APX 400", [F8T, 1.53]],
  ["Vista 400", [F8T, 1.30]],
  ["Ultra 100", [F8T, 1.20]],
  ["P33", [F8T, 1.40]],
  ["Earl Grey 100", [F8T, 1.70]],
  ["Lady Grey 400", [F8T, 1.62]],
  ["Potsdam Kino 100", [F8T, 1.35]],
  ["Berlin Kino 400", [F8T, 1.35]],
  ["PORTRA 400", [METERGEIST, 1.30]],
  ["PORTRA 160", [METERGEIST, 1.30]],
  ["GOLD 200", [METERGEIST, 1.30]],
  ["ULTRAMAX 400", [METERGEIST, 1.30]],
  ["EKTAR 100", [METERGEIST, 1.18]],
  ["Color Negative 400", [METERGEIST, 1.30]],
  ["Color Negative 800", [METERGEIST, 1.30]],
  ["EKTACHROME E100", [METERGEIST, 1.20]],
  ["PRO 400H", [METERGEIST, 1.25]],
  ["VELVIA 50", [METERGEIST, 1.25]],
  ["PROVIA 100F", [METERGEIST, 1.22]],
]);

export function onlineReciprocity(name, time, unchangedThrough = 1) {
  const model = models.get(name);
  if (!model || time <= unchangedThrough) return null;
  const [source, power, points = []] = model;
  let input = unchangedThrough, output = unchangedThrough;
  for (const [next, corrected] of points) {
    if (next <= unchangedThrough) continue;
    if (time <= next) return {
      source, seconds: output * (corrected / output) ** (Math.log(time / input) / Math.log(next / input)),
      extrapolated: false, network: true,
    };
    input = next;
    output = corrected;
  }
  return {
    source, seconds: output * (time / input) ** power,
    extrapolated: time > (points.at(-1)?.[0] ?? Math.max(50, unchangedThrough)), network: true,
  };
}

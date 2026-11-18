// Local residual registration after the inertial / rectilinear projection.
// OpenCV is initialized by the caller, normally in a classic dedicated Worker.
// The returned similarity maps REFERENCE coordinates into INCOMING coordinates:
// u = a*x - b*y + tx; v = b*x + a*y + ty. No homography is applied to pixels.

const REPROJECTION = 1.6;
const clock = () => globalThis.performance?.now?.() ?? Date.now();

function fitSimilarity(points) {
  if (points.length < 2) return null;
  let x = 0, y = 0, u = 0, v = 0;
  for (const p of points) { x += p.x; y += p.y; u += p.u; v += p.v; }
  const count = points.length;
  x /= count; y /= count; u /= count; v /= count;
  let denominator = 0, real = 0, imaginary = 0;
  for (const p of points) {
    const dx = p.x - x, dy = p.y - y, du = p.u - u, dv = p.v - v;
    denominator += dx * dx + dy * dy;
    real += dx * du + dy * dv;
    imaginary += dx * dv - dy * du;
  }
  if (denominator < 1e-6) return null;
  const a = real / denominator, b = imaginary / denominator;
  return { a, b, x: u - a * x + b * y, y: v - b * x - a * y };
}

function residual(point, model) {
  return Math.hypot(model.a * point.x - model.b * point.y + model.x - point.u,
    model.b * point.x + model.a * point.y + model.y - point.v);
}

function residualGrid(points, model, width, height) {
  const offsetsX = new Float64Array(16), offsetsY = new Float64Array(16);
  const gridSupport = new Uint8Array(16);
  const residuals = points.map(p => ({ ...p,
    dx: p.u - (model.a * p.x - model.b * p.y + model.x),
    dy: p.v - (model.b * p.x + model.a * p.y + model.y) }));
  for (let row = 0; row < 4; row++) for (let col = 0; col < 4; col++) {
    const cx = col * width / 3, cy = row * height / 3;
    // Use all forward/backward-consistent LK points, including RANSAC outliers:
    // locally coherent parallax need not fit the global similarity exactly.
    const nearby = residuals.filter(p => Math.abs(p.x - cx) < width * .25 &&
      Math.abs(p.y - cy) < height * .25);
    if (nearby.length < 4) continue;
    const xs = nearby.map(p => p.dx).sort((a, b) => a - b);
    const ys = nearby.map(p => p.dy).sort((a, b) => a - b);
    const quartile = values => values[Math.floor(values.length * .75)] - values[Math.floor(values.length * .25)];
    if (quartile(xs) > 1.5 || quartile(ys) > 1.5) continue;
    const dx = xs[Math.floor(xs.length / 2)], dy = ys[Math.floor(ys.length / 2)];
    // A small moving subject must not create a local deformation merely by
    // supplying four corners. Require support across the node's neighborhood;
    // discard a large independent motion before applying the ±3 px bound.
    const spanX = Math.max(...nearby.map(p => p.x)) - Math.min(...nearby.map(p => p.x));
    const spanY = Math.max(...nearby.map(p => p.y)) - Math.min(...nearby.map(p => p.y));
    const neighborhoodX = Math.min(width, cx + width * .25) - Math.max(0, cx - width * .25);
    const neighborhoodY = Math.min(height, cy + height * .25) - Math.max(0, cy - height * .25);
    if (spanX < neighborhoodX * .45 || spanY < neighborhoodY * .45 || Math.hypot(dx, dy) > 4.5) continue;
    const i = row * 4 + col;
    offsetsX[i] = Math.max(-3, Math.min(3, dx)) / width;
    offsetsY[i] = Math.max(-3, Math.min(3, dy)) / height;
    gridSupport[i] = nearby.length;
  }
  return { offsetsX, offsetsY, gridSupport, width, height };
}

// A textured moving subject in an otherwise flat frame must not determine the
// camera correction. Evaluate spread against the usable overlap, not the entire
// image, so an actual narrow strip of overlap remains usable.
function spatiallyDistributed(points, mask, width, height) {
  let left = width, top = height, right = -1, bottom = -1;
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++)
    if (mask[y * width + x]) {
      left = Math.min(left, x); right = Math.max(right, x);
      top = Math.min(top, y); bottom = Math.max(bottom, y);
    }
  if (right < left || bottom < top) return false;
  const wide = right - left + 1, high = bottom - top + 1;
  let minX = width, maxX = 0, minY = height, maxY = 0;
  const cells = new Set(), rows = new Set(), columns = new Set();
  for (const p of points) {
    minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
    minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
    const col = Math.max(0, Math.min(2, Math.floor(3 * (p.x - left) / wide)));
    const row = Math.max(0, Math.min(2, Math.floor(3 * (p.y - top) / high)));
    cells.add(row * 3 + col); rows.add(row); columns.add(col);
  }
  const spanX = maxX - minX, spanY = maxY - minY;
  return spanX >= Math.max(12, .35 * wide) && spanY >= Math.max(12, .35 * high) &&
    spanX * spanY >= .20 * wide * high && cells.size >= 4 && rows.size >= 2 && columns.size >= 2;
}

/**
 * Align two equally sized, single-channel Uint8Array images (maximum edge 256).
 * mask is their common valid region; zero/transparent projection holes must be 0.
 * Returns null on unreliable input / unsupported OpenCV / any OpenCV exception.
 * Ownership of all input arrays stays with the caller; all CV Mats are deleted.
 */
export function alignOverlap(cv, reference, incoming, mask, width, height) {
  const start = clock(), area = width * height;
  const bytes = value => ArrayBuffer.isView(value) && value.BYTES_PER_ELEMENT === 1 && value.length === area;
  if (!Number.isInteger(width) || !Number.isInteger(height) ||
      Math.min(width, height) < 32 || Math.max(width, height) > 256 ||
      !bytes(reference) || !bytes(incoming) || !bytes(mask) ||
      !cv?.Mat || !cv.goodFeaturesToTrack || !cv.calcOpticalFlowPyrLK ||
      (!cv.estimateAffinePartial2D && !cv.findHomography)) return null;

  const owned = [], own = mat => { owned.push(mat); return mat; };
  try {
    const previous = own(cv.matFromArray(height, width, cv.CV_8UC1, reference));
    const next = own(cv.matFromArray(height, width, cv.CV_8UC1, incoming));
    const validity = own(cv.matFromArray(height, width, cv.CV_8UC1, mask));
    const safeMask = own(new cv.Mat());
    const kernel = own(cv.getStructuringElement(cv.MORPH_RECT, new cv.Size(13, 13)));
    cv.erode(validity, safeMask, kernel, new cv.Point(-1, -1), 1, cv.BORDER_CONSTANT, new cv.Scalar(0));
    const corners = own(new cv.Mat());
    cv.goodFeaturesToTrack(previous, corners, 100, .015, 5, safeMask, 3, false, .04);
    if (corners.rows < 7) return null;

    const forward = own(new cv.Mat()), forwardStatus = own(new cv.Mat()), forwardError = own(new cv.Mat());
    const backward = own(new cv.Mat()), backwardStatus = own(new cv.Mat()), backwardError = own(new cv.Mat());
    const window = new cv.Size(15, 15);
    const criteria = new cv.TermCriteria(cv.TermCriteria_COUNT | cv.TermCriteria_EPS, 25, .01);
    cv.calcOpticalFlowPyrLK(previous, next, corners, forward, forwardStatus, forwardError, window, 2, criteria);
    cv.calcOpticalFlowPyrLK(next, previous, forward, backward, backwardStatus, backwardError, window, 2, criteria);

    const points = [];
    for (let i = 0; i < corners.rows; i++) {
      if (!forwardStatus.data[i] || !backwardStatus.data[i]) continue;
      const x = corners.data32F[2 * i], y = corners.data32F[2 * i + 1];
      const u = forward.data32F[2 * i], v = forward.data32F[2 * i + 1];
      const bx = backward.data32F[2 * i], by = backward.data32F[2 * i + 1];
      if (![x, y, u, v, bx, by].every(Number.isFinite) ||
          u < 0 || v < 0 || u >= width || v >= height ||
          !safeMask.data[Math.floor(v) * width + Math.floor(u)] || Math.hypot(bx - x, by - y) >= 1.2) continue;
      points.push({ x, y, u, v });
    }
    if (points.length < 7) return null;
    // Reserve points across the corner list when overlap has enough support.
    // The fit must agree with tracks that never participated in RANSAC.
    const heldOut = points.length >= 16 ? points.filter((_, i) => i % 4 === 0) : [];
    const training = heldOut.length ? points.filter((_, i) => i % 4 !== 0) : points;
    const required = Math.max(7, Math.ceil(.65 * training.length));
    const from = own(cv.matFromArray(training.length, 1, cv.CV_32FC2, training.flatMap(p => [p.x, p.y])));
    const to = own(cv.matFromArray(training.length, 1, cv.CV_32FC2, training.flatMap(p => [p.u, p.v])));
    const inlierMask = own(new cv.Mat());
    const model = own(cv.estimateAffinePartial2D
      ? cv.estimateAffinePartial2D(from, to, inlierMask, cv.RANSAC, REPROJECTION, 2000, .995, 10)
      : cv.findHomography(from, to, cv.RANSAC, REPROJECTION, inlierMask, 2000, .995));
    if (!model || model.empty() || inlierMask.data.length !== training.length) return null;
    let inliers = training.filter((_, i) => inlierMask.data[i]);
    if (inliers.length < required) return null;
    // The homography is only an outlier selector. Refit strictly a similarity,
    // then reject points that only agreed with a projective / shearing model.
    let similarity;
    for (let iteration = 0; iteration < 2; iteration++) {
      similarity = fitSimilarity(inliers);
      if (!similarity) return null;
      inliers = inliers.filter(p => residual(p, similarity) <= REPROJECTION);
      if (inliers.length < required) return null;
    }
    similarity = fitSimilarity(inliers);
    if (!similarity || !Object.values(similarity).every(Number.isFinite)) return null;
    if (heldOut.length) {
      const verified = heldOut.filter(point => residual(point, similarity) <= REPROJECTION);
      if (verified.length < Math.ceil(.6 * heldOut.length)) return null;
      inliers = inliers.concat(verified);
      if (inliers.length < Math.ceil(.65 * points.length)) return null;
      similarity = fitSimilarity(inliers);
      if (!similarity) return null;
    }
    const scale = Math.hypot(similarity.a, similarity.b);
    if (scale < .95 || scale > 1.05 || Math.abs(similarity.b) >= .08 ||
        Math.hypot(similarity.x, similarity.y) >= .15 * Math.min(width, height) ||
        !spatiallyDistributed(inliers, safeMask.data, width, height)) return null;
    const grid = residualGrid(points, similarity, width, height);
    return { ...similarity, ...grid, matched: true, inliers: inliers.length,
      tracked: points.length, ms: clock() - start };
  } catch {
    return null;
  } finally {
    for (let i = owned.length - 1; i >= 0; i--)
      try { owned[i]?.delete(); } catch { /* Release remaining Mats even after an exception. */ }
  }
}

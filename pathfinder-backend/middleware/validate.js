// ─────────────────────────────────────────────────────
// pathfinder-backend/middleware/validate.js
// Input validation for API query parameters
// ─────────────────────────────────────────────────────

/**
 * Validates that a value is a valid latitude (-90 to 90)
 */
function isValidLat(val) {
  const n = parseFloat(val);
  return !isNaN(n) && n >= -90 && n <= 90;
}

/**
 * Validates that a value is a valid longitude (-180 to 180)
 */
function isValidLng(val) {
  const n = parseFloat(val);
  return !isNaN(n) && n >= -180 && n <= 180;
}

/**
 * Validates that a value is a positive number
 */
function isPositiveNum(val) {
  const n = parseFloat(val);
  return !isNaN(n) && n > 0;
}

/**
 * Middleware: Validate route-graph query params
 * Required: startLat, startLng, endLat, endLng
 * Optional: padding (positive number, max 5)
 */
function validateRouteGraph(req, res, next) {
  const { startLat, startLng, endLat, endLng, padding } = req.query;
  const errors = [];

  if (!startLat || !isValidLat(startLat)) errors.push("startLat must be a valid latitude (-90 to 90)");
  if (!startLng || !isValidLng(startLng)) errors.push("startLng must be a valid longitude (-180 to 180)");
  if (!endLat || !isValidLat(endLat)) errors.push("endLat must be a valid latitude (-90 to 90)");
  if (!endLng || !isValidLng(endLng)) errors.push("endLng must be a valid longitude (-180 to 180)");
  if (padding && !isPositiveNum(padding)) errors.push("padding must be a positive number (km)");

  if (errors.length > 0) {
    return res.status(400).json({ error: "Validation failed", details: errors });
  }
  next();
}

/**
 * Middleware: Validate subgraph (radius) query params
 * Required: lat, lng
 * Optional: radius (positive number, max 5000)
 */
function validateSubgraph(req, res, next) {
  const { lat, lng, radius } = req.query;
  const errors = [];

  if (!lat || !isValidLat(lat)) errors.push("lat must be a valid latitude");
  if (!lng || !isValidLng(lng)) errors.push("lng must be a valid longitude");
  if (radius && (!isPositiveNum(radius) || parseFloat(radius) > 10000)) {
    errors.push("radius must be 0-10000 meters");
  }

  if (errors.length > 0) {
    return res.status(400).json({ error: "Validation failed", details: errors });
  }
  next();
}

/**
 * Middleware: Validate bbox query params
 * Required: south, west, north, east
 */
function validateBbox(req, res, next) {
  const { south, west, north, east } = req.query;
  const errors = [];

  if (!south || !isValidLat(south)) errors.push("south must be a valid latitude");
  if (!west || !isValidLng(west)) errors.push("west must be a valid longitude");
  if (!north || !isValidLat(north)) errors.push("north must be a valid latitude");
  if (!east || !isValidLng(east)) errors.push("east must be a valid longitude");

  if (errors.length === 0) {
    if (parseFloat(south) >= parseFloat(north)) errors.push("south must be less than north");
    if (parseFloat(west) >= parseFloat(east)) errors.push("west must be less than east");
  }

  if (errors.length > 0) {
    return res.status(400).json({ error: "Validation failed", details: errors });
  }
  next();
}

/**
 * Middleware: Validate nearest node query params
 */
function validateNearest(req, res, next) {
  const { lat, lng } = req.query;
  const errors = [];

  if (!lat || !isValidLat(lat)) errors.push("lat must be a valid latitude");
  if (!lng || !isValidLng(lng)) errors.push("lng must be a valid longitude");

  if (errors.length > 0) {
    return res.status(400).json({ error: "Validation failed", details: errors });
  }
  next();
}

module.exports = {
  validateRouteGraph,
  validateSubgraph,
  validateBbox,
  validateNearest,
};

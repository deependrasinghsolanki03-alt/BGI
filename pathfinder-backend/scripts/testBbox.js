require('dotenv').config();
const mongoose = require('mongoose');

async function main() {
  await mongoose.connect(process.env.MONGO_URI);
  const col = mongoose.connection.db.collection('osmnodes');
  
  // Find the nearest node to Delhi center
  const node = await col.findOne({
    location: {
      $nearSphere: {
        $geometry: { type: "Point", coordinates: [77.209, 28.6139] },
        $maxDistance: 5000
      }
    }
  });
  console.log('Nearest node to Delhi center:');
  console.log(`  Coords: [${node.location.coordinates}]`);
  console.log(`  NodeId: ${node.nodeId}`);
  
  // Now test Polygon bbox centered on THIS node
  const lng = node.location.coordinates[0];
  const lat = node.location.coordinates[1];
  const d = 0.02; // ~2km
  
  console.log(`\nPolygon bbox around [${lat.toFixed(4)}, ${lng.toFixed(4)}] ±${d}°:`);
  const t1 = Date.now();
  const count = await col.countDocuments({
    location: { $geoWithin: { $geometry: {
      type: "Polygon",
      coordinates: [[[lng-d,lat-d],[lng+d,lat-d],[lng+d,lat+d],[lng-d,lat+d],[lng-d,lat-d]]]
    }}}
  });
  console.log(`  ${count} nodes (${Date.now()-t1}ms)`);
  
  // Test slightly bigger box
  const d2 = 0.05; // ~5km
  console.log(`\nPolygon bbox around [${lat.toFixed(4)}, ${lng.toFixed(4)}] ±${d2}°:`);
  const t2 = Date.now();
  const count2 = await col.countDocuments({
    location: { $geoWithin: { $geometry: {
      type: "Polygon",
      coordinates: [[[lng-d2,lat-d2],[lng+d2,lat-d2],[lng+d2,lat+d2],[lng-d2,lat+d2],[lng-d2,lat-d2]]]
    }}}
  });
  console.log(`  ${count2} nodes (${Date.now()-t2}ms)`);

  process.exit(0);
}

main().catch(err => { console.error(err); process.exit(1); });

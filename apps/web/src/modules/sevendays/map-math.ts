export const MAP_WIDTH = 1000,
  MAP_HEIGHT = 450,
  TILE_PIXELS = 160;
export function tileSpan(blockSize: number, maxZoom: number, zoom: number) {
  return blockSize * 2 ** (maxZoom - zoom);
}
export function visibleTiles(cx: number, cz: number, span: number) {
  const halfX = ((MAP_WIDTH / TILE_PIXELS) * span) / 2,
    halfZ = ((MAP_HEIGHT / TILE_PIXELS) * span) / 2;
  const tiles: { x: number; z: number }[] = [];
  for (let x = Math.floor((cx - halfX) / span); x <= Math.floor((cx + halfX) / span); x++)
    for (let z = Math.floor((cz - halfZ) / span); z <= Math.floor((cz + halfZ) / span); z++)
      if (Math.abs(x) <= 65536 && Math.abs(z) <= 65536) tiles.push({ x, z });
  return tiles;
}
export function mapPoint(x: number, z: number, cx: number, cz: number, span: number) {
  return {
    x: MAP_WIDTH / 2 + ((x - cx) / span) * TILE_PIXELS,
    y: MAP_HEIGHT / 2 - ((z - cz) / span) * TILE_PIXELS,
  };
}

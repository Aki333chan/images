import { mapPoint, tileSpan, visibleTiles } from './map-math';
describe('7DTD native map projection', () => {
  it('uses one block per pixel at maximum native zoom', () => {
    expect(tileSpan(128, 4, 4)).toBe(128);
    expect(tileSpan(128, 4, 0)).toBe(2048);
  });
  it('places north above and east to the right', () => {
    expect(mapPoint(-10, -20, -10, -20, 128)).toEqual({ x: 500, y: 225 });
    expect(mapPoint(118, 108, -10, -20, 128)).toEqual({ x: 660, y: 65 });
  });
  it('floors negative indices and keeps each viewport bounded', () => {
    const tiles = visibleTiles(-1, -1, 128);
    expect(tiles).toContainEqual({ x: -4, z: -2 });
    expect(tiles).toContainEqual({ x: -1, z: -1 });
    expect(tiles.length).toBeLessThanOrEqual(40);
    expect(visibleTiles(1e9, 1e9, 128)).toEqual([]);
  });
});

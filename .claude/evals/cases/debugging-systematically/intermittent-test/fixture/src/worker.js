// Stand-in for the thumbnail service, which usually answers within 5-55ms.
export async function makeThumbnail(name) {
  const latency = 5 + Math.floor(Math.random() * 50);
  await new Promise((resolve) => setTimeout(resolve, latency));
  return name + '.thumb.png';
}

/**
 * Makes a phone photo of a report much smaller before it is uploaded: a 3 MB photo becomes about 300 KB and is still
 * easy to read. The longest side is brought down to {@link MAX_SIDE} pixels and the picture is saved as a JPEG.
 * Anything it can't or needn't shrink (PDFs, photos that are already small, formats the browser can't read such as
 * HEIC, or a result that wouldn't be clearly smaller) is returned unchanged, so uploads never fail because of this.
 */
const MAX_SIDE = 1800;
const QUALITY = 0.75;
const ALREADY_SMALL = 300 * 1024;
const SHRINKABLE = ['image/jpeg', 'image/png', 'image/webp'];

export async function shrinkImage(file: File): Promise<File> {
  if (!SHRINKABLE.includes(file.type) || file.size <= ALREADY_SMALL) return file;
  try {
    const picture = await decode(file);
    const scale = Math.min(1, MAX_SIDE / Math.max(picture.width, picture.height));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(picture.width * scale));
    canvas.height = Math.max(1, Math.round(picture.height * scale));
    const ctx = canvas.getContext('2d');
    if (!ctx) return file;
    // JPEG has no see-through parts: screenshots with transparency would turn black without this
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(picture.source, 0, 0, canvas.width, canvas.height);
    picture.release();
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', QUALITY));
    // Keep the original unless the new one is clearly smaller
    if (!blob || blob.size > file.size * 0.9) return file;
    return new File([blob], file.name.replace(/\.[^.]+$/, '') + '.jpg', { type: 'image/jpeg', lastModified: file.lastModified });
  } catch {
    return file;
  }
}

interface Picture {
  source: CanvasImageSource;
  width: number;
  height: number;
  release(): void;
}

/** Reads the picture the way it is meant to be seen (turned upright by its camera information). */
async function decode(file: File): Promise<Picture> {
  if (typeof createImageBitmap === 'function') {
    try {
      const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' } as ImageBitmapOptions);
      return { source: bitmap, width: bitmap.width, height: bitmap.height, release: () => bitmap.close() };
    } catch {
      // older browsers: fall back to an image element below
    }
  }
  const url = URL.createObjectURL(file);
  try {
    const img = new Image();
    img.src = url;
    await img.decode();
    return { source: img, width: img.naturalWidth, height: img.naturalHeight, release: () => URL.revokeObjectURL(url) };
  } catch (e) {
    URL.revokeObjectURL(url);
    throw e;
  }
}

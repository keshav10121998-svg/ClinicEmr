import { shrinkImage } from './shrinkImage';
import { shrinkPdf } from './shrinkPdf';

/**
 * Makes a file about to be uploaded smaller where that is safe: phone photos and big scanned PDFs. Anything else, and
 * anything that can't be shrunk well, comes back unchanged, and it never throws, so an upload never fails because of it.
 */
export async function shrinkFile(file: File): Promise<File> {
  return file.type === 'application/pdf' || /\.pdf$/i.test(file.name) ? shrinkPdf(file) : shrinkImage(file);
}

// HF Digital Modes (GPL v3): the app's way in to Robot36's SSTV decoder. Robot36's own files in this folder are
// copied unchanged (0BSD, LICENSE here); this one is not part of Robot36. It sits in Robot36's package because the
// Decoder's constructor and current mode are package-private, and it holds the two pixel buffers as Robot36's
// MainActivity does: the "scope" - every scan line as it is decoded, a picture or not - and the picture being received
// (sized by the mode once its VIS code is heard).
package xdsopl.robot36;

public class SstvDecoder {
    /** Every scan line decoded, as Robot36's scope: a ring 1280 lines deep, kept twice over so a window never wraps. */
    public final PixelBuffer scope = new PixelBuffer(640, 2 * 1280);
    /** The picture being received: its width and height are the mode's; line is how far it has got (-1 none, height = done). */
    public final PixelBuffer image = new PixelBuffer(800, 616);
    private final Decoder decoder;                    // Robot36's decoder

    /** A decoder for audio at [sampleRate] Hz (any rate: Robot36 sizes its filters by it). */
    public SstvDecoder(int sampleRate) { decoder = new Decoder(scope, image, "Raw", sampleRate); }

    /** One block of mono audio, -1..1 (the decoder works in place on it). True if new lines were decoded. */
    public boolean process(float[] samples) { return decoder.process(samples, 0); }

    /** The mode heard (or expected) now, e.g. "Robot 36 Color", "Martin 1". */
    public String modeName() { return decoder.currentMode.getName(); }

    /** Decode only [name] (a mode's name, as modeName gives), or null to follow the VIS code (automatic). */
    public void setMode(String name) { decoder.setMode(name); }
}

package com.aiusage.monitor.bridge;

/**
 * An offer that arrived as text: the deep link the system hands the pairing
 * activity, or something the user pasted. Both are "a string came in", so both live
 * here, and the Android half (the intent, the clipboard) stays in the activity where
 * the {@code Context} is — this class takes the text and nothing else.
 *
 * <p>The two differ only in what they call themselves, and that difference is worth
 * two types rather than a parameter: the label is what the pairing screen shows as
 * the chosen channel, and a user who pasted should not be told they arrived by link.
 */
public final class TextOfferSource implements PairingPayloadSource {

    private final String label;
    private final String text;

    TextOfferSource(String label, String text) {
        this.label = label;
        this.text = text;
    }

    /** The {@code aiusage://pair#…} link the system delivered to the activity. */
    public static TextOfferSource fromDeepLink(String uri) {
        return new TextOfferSource("系统转来的配对链接", uri);
    }

    /** Whatever the user pasted, including the bare tail without the prefix. */
    public static TextOfferSource fromPaste(String pasted) {
        return new TextOfferSource("粘贴的配对内容", pasted);
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public PairingOffer read() throws PairingPayload.Invalid {
        if (text == null || text.trim().isEmpty()) {
            // An empty read is "nothing arrived", which the screen reports as such.
            // Turning it into a parse error would blame the user for a system that
            // handed over nothing.
            throw new PairingPayload.Invalid("没有收到配对内容");
        }
        return PairingOffer.fromPayload(PairingPayload.parse(text));
    }
}

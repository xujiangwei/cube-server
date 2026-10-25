package cube.service.harness.skill;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/** Static HTML only: never loads a URL, embedded resource, script, stylesheet or browser. */
final class HtmlText {
    final String title, text;
    private HtmlText(String title, String text) { this.title = title; this.text = text; }
    static HtmlText parse(String html) {
        final StringBuilder body = new StringBuilder(), title = new StringBuilder();
        final Deque<String> hidden = new ArrayDeque<>();
        final boolean[] inTitle = {false};
        try {
            new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
                public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                    String name = tag.toString();
                    if (name.equals("script") || name.equals("style") || name.equals("head")) hidden.push(name);
                    if (name.equals("title")) inTitle[0] = true;
                    if (tag.isBlock()) body.append('\n');
                }
                public void handleEndTag(HTML.Tag tag, int pos) {
                    String name = tag.toString();
                    if (!hidden.isEmpty() && hidden.peek().equals(name)) hidden.pop();
                    if (name.equals("title")) inTitle[0] = false;
                    if (tag.isBlock()) body.append('\n');
                }
                public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                    if (tag == HTML.Tag.BR || tag == HTML.Tag.HR) body.append('\n');
                }
                public void handleText(char[] data, int pos) {
                    if (inTitle[0]) title.append(data).append(' ');
                    else if (hidden.isEmpty()) body.append(data).append(' ');
                }
            }, true);
        } catch (IOException e) { throw new IllegalArgumentException("HTML_PARSE_FAILED"); }
        return new HtmlText(clean(title.toString()), clean(body.toString()));
    }
    private static String clean(String value) { return value.replaceAll("[\\s\\u00a0]+", " ").trim(); }
}

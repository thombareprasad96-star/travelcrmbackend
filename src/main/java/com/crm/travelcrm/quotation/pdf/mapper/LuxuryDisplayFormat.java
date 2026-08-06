package com.crm.travelcrm.quotation.pdf.mapper;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns raw quotation values into the finished strings the LUXURY template prints.
 *
 * <p><b>Why this is a Java bean and not Thymeleaf expressions.</b> Chromium renders inside whatever
 * container it is given, with whatever default locale that container has. {@code #numbers.format*}
 * would therefore group digits Western-style ({@code ₹125,000}) on a server whose locale is not
 * {@code en-IN}, while the same code produced {@code ₹1,25,000} on a developer's machine — the same
 * number, two different documents, and no test that fails. Pinning {@link #INDIA} here makes the
 * output independent of the environment.
 *
 * <p><b>The ₹ glyph is safe here and only here.</b> {@code PdfFormat} prints {@code "Rs."} because
 * OpenPDF's base-14 fonts lack U+20B9. Chromium uses real system fonts, so Luxury can print the
 * actual sign — provided the image ships a font that carries it (see the Dockerfile).
 *
 * <p><b>Zero is a value, not an absence.</b> {@link #money(BigDecimal)} renders {@code 0} as
 * {@code ₹0} rather than a dash or an empty string. A quotation with no discount must say so; a
 * document that simply omits the discount line leaves the customer unable to derive the total from
 * what is printed. Callers decide whether a row appears — this class never decides it for them.
 */
@Component
public class LuxuryDisplayFormat {

    /**
     * The internal marker {@link #bullets} substitutes for every line-ending tag before splitting.
     *
     * <p>A NUL is used because it cannot occur in text an agent typed or pasted, so it can never be
     * mistaken for content. Written as an escape rather than as a literal control character in the
     * source, where it would be invisible to every reviewer.
     */
    private static final String LINE_MARK = "\0";

    private static final Locale INDIA = Locale.forLanguageTag("en-IN");
    private static final DateTimeFormatter DAY_MONTH_YEAR =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    /**
     * The date shapes the quotation carries. Section rows store dates as free-form strings written
     * by the builder (ISO from a date input, sometimes {@code dd/MM/yyyy} from a hand edit), so
     * parsing tries each rather than assuming one and printing a raw {@code 2026-08-20} when wrong.
     */
    private static final List<DateTimeFormatter> INBOUND_DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ENGLISH));

    // ── Money ─────────────────────────────────────────────────────────────────

    /**
     * {@code ₹76,650} — Indian digit grouping, no decimals.
     *
     * <p>Decimals are dropped deliberately: these are package prices in whole rupees, and
     * {@code ₹1,25,000.00} reads like an invoice line rather than a headline figure. Rounding is
     * HALF_UP on the display value only — the stored amount is untouched.
     *
     * <p>Null becomes {@code ₹0}, not {@code null} or {@code "-"}. A missing amount on the money
     * page is always a bug in the caller, and printing ₹0 makes it visible instead of hiding it
     * behind a dash that looks intentional.
     */
    public String money(BigDecimal value) {
        BigDecimal v = value != null ? value : BigDecimal.ZERO;
        BigDecimal rounded = v.setScale(0, java.math.RoundingMode.HALF_UP);
        String sign = rounded.signum() < 0 ? "-" : "";
        return sign + "₹" + groupIndian(rounded.abs().toPlainString());
    }

    /**
     * Lakh/crore digit grouping: three digits, then twos — {@code 10,50,000}, not {@code 1,050,000}.
     *
     * <p><b>Hand-written because neither of the obvious approaches works.</b>
     * {@code NumberFormat.getNumberInstance(en-IN)} groups in plain thousands like every other
     * English locale (it produced {@code ₹100,000} — right digits, wrong reading). The natural next
     * move, a {@code "#,##,##0"} DecimalFormat pattern, does not work either: DecimalFormat carries a
     * single {@code groupingSize} and simply takes the last interval in the pattern, so the extra
     * comma is ignored and it grouped by threes as well ({@code ₹1,050,000}).
     *
     * <p>Both failures produce a plausible-looking number, which is why they survived until a test
     * asserted the exact string. That is also why this is not inlined into {@link #money} — the rule
     * deserves to be visible and directly testable.
     *
     * @param digits an unsigned, decimal-free digit string
     */
    private static String groupIndian(String digits) {
        if (digits.length() <= 3) return digits;

        String last3 = digits.substring(digits.length() - 3);
        String head = digits.substring(0, digits.length() - 3);

        StringBuilder sb = new StringBuilder();
        int i = head.length();
        while (i > 2) {                 // take two digits off the right of the head, repeatedly
            sb.insert(0, head, i - 2, i);
            sb.insert(0, ',');
            i -= 2;
        }
        if (i > 0) sb.insert(0, head, 0, i);   // the leading 1 or 2 digits
        return sb + "," + last3;
    }

    /** {@code 18%} / {@code 0%} — trailing {@code .00} stripped so whole percentages read cleanly. */
    public String percent(BigDecimal value) {
        BigDecimal v = value != null ? value : BigDecimal.ZERO;
        return v.stripTrailingZeros().toPlainString() + "%";
    }

    // ── Dates ─────────────────────────────────────────────────────────────────

    /** {@code 20 Aug 2026}, or null when there is no date (the template then hides the field). */
    public String date(LocalDate date) {
        return date == null ? null : date.format(DAY_MONTH_YEAR);
    }

    /**
     * Parses one of the builder's date strings and reformats it. Returns the input unchanged when
     * it matches none of the known shapes — a hand-typed "Late August" is better printed as written
     * than swallowed, and it can never become a raw ISO timestamp this way.
     */
    public String date(String raw) {
        LocalDate parsed = parse(raw);
        return parsed != null ? parsed.format(DAY_MONTH_YEAR) : blankToNull(raw);
    }

    /**
     * {@code 20 Aug 2026 – 26 Aug 2026}, collapsing to a single date when both ends are the same
     * day and to whichever end exists when only one does. En dash, not a hyphen: this is a range.
     */
    public String dateRange(LocalDate from, LocalDate to) {
        String a = date(from);
        String b = date(to);
        if (a == null && b == null) return null;
        if (a == null) return b;
        if (b == null) return a;
        return a.equals(b) ? a : a + " – " + b;
    }

    /** Parse helper shared with the mapper for nights/date arithmetic. Null when unparseable. */
    public LocalDate parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        // An ISO date-time ("2026-08-20T00:00") — take the date half rather than failing to parse.
        int t = s.indexOf('T');
        if (t == 10) s = s.substring(0, 10);
        for (DateTimeFormatter f : INBOUND_DATE_FORMATS) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        return null;
    }

    // ── Counts & labels ───────────────────────────────────────────────────────

    /** {@code 6 Nights / 7 Days}. Null when neither number is known. */
    public String duration(Integer nights, Integer days) {
        boolean hasNights = nights != null && nights > 0;
        boolean hasDays = days != null && days > 0;
        if (!hasNights && !hasDays) return null;
        if (!hasNights) return plural(days, "Day");
        if (!hasDays) return plural(nights, "Night");
        return plural(nights, "Night") + " / " + plural(days, "Day");
    }

    /** {@code 2 Adults, 1 Child}. Null when nobody is travelling (so the tile is hidden). */
    public String travellers(Integer adults, Integer children, Integer infants) {
        List<String> parts = new ArrayList<>();
        if (adults != null && adults > 0) parts.add(plural(adults, "Adult"));
        if (children != null && children > 0) parts.add(children + (children == 1 ? " Child" : " Children"));
        if (infants != null && infants > 0) parts.add(plural(infants, "Infant"));
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** {@code 2 Rooms} / {@code 1 Room}. Null when zero or unknown — never "0 Rooms". */
    public String rooms(Integer count) {
        return (count == null || count <= 0) ? null : plural(count, "Room");
    }

    /** {@code 4 Nights} for a hotel stay. Null when the span is unknown. */
    public String nights(Integer count) {
        return (count == null || count <= 0) ? null : plural(count, "Night");
    }

    /** {@code ★★★★☆} for a 4-star hotel. Null when unrated, so no empty star row prints. */
    public String stars(Integer rating) {
        if (rating == null || rating <= 0) return null;
        int filled = Math.min(rating, 5);
        return "★".repeat(filled) + "☆".repeat(5 - filled);
    }

    /**
     * Turns an enum-ish code into a human label: {@code PARTIALLY_PAID} → {@code Partially Paid}.
     * Used for anything that would otherwise print a SCREAMING_SNAKE constant at a customer.
     */
    public String label(Enum<?> value) {
        return value == null ? null : label(value.name());
    }

    public String label(String code) {
        if (code == null || code.isBlank()) return null;
        String[] words = code.trim().replace('-', '_').split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0)))
              .append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /**
     * Flattens the builder's rich-text HTML into plain text.
     *
     * <p>Chromium would happily render the markup, which is exactly the problem: those fields are
     * user-authored contentEditable output, so leaving them as HTML lets a stray {@code <div>} with
     * its own styling break an A4 page layout that depends on fixed heights. Same intent as
     * {@code PdfFormat.plain}, kept separate because this one does not have to produce XML-safe
     * output for a different engine.
     */
    public String plain(String html) {
        if (html == null || html.isBlank()) return null;
        String s = html
                .replaceAll("(?i)<br\\s*/?>", " ")
                .replaceAll("(?i)<li[^>]*>", " • ")
                .replaceAll("(?i)</(p|div|li|ul|ol|h[1-6])>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        s = s.replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Splits rich text into the LINES it was written as, so a bulleted description prints as bullets.
     *
     * <p><b>Why this exists next to {@link #plain}.</b> {@code plain} flattens everything into one
     * paragraph, which is right for a one-line caption and wrong for a description: agents write
     * these in the builder's rich-text editor as {@code <ul><li>…</li></ul>}, and flattening turned a
     * six-point itinerary into a single run-on sentence with inline bullet characters. The structure
     * the agent typed is information, not decoration.
     *
     * <p>Splitting still happens in Java rather than passing the HTML through to Chromium. The
     * source is user-authored contentEditable output — arbitrary nested markup with its own inline
     * styles — and injecting that into a fixed-height A4 page is how a stray {@code <div>} silently
     * pushes content off the sheet. Here the markup only ever decides where a line ENDS; the
     * template supplies the list.
     *
     * <p>Returns an empty list for absent or empty input, so {@code th:each} renders nothing.
     */
    public List<String> bullets(String html) {
        if (html == null || html.isBlank()) return List.of();

        // Every construct that ends a visual line becomes one separator, then split on it. Ordered
        // longest-tag-first so </li> is consumed before the generic tag strip below can eat it.
        String marked = html
                .replaceAll("(?i)</li\\s*>", LINE_MARK)
                .replaceAll("(?i)</p\\s*>", LINE_MARK)
                .replaceAll("(?i)</div\\s*>", LINE_MARK)
                .replaceAll("(?i)</h[1-6]\\s*>", LINE_MARK)
                .replaceAll("(?i)<br\\s*/?>", LINE_MARK);

        List<String> lines = new ArrayList<>();
        for (String piece : marked.split(LINE_MARK)) {
            String cleaned = plain(piece);
            // plain() maps a surviving <li> to a leading "• "; the template draws its own markers,
            // so a doubled bullet would print as "• • Visit the fort".
            if (cleaned != null) cleaned = cleaned.replaceFirst("^(?:[•·●-]\\s*)+", "").trim();
            if (cleaned != null && !cleaned.isEmpty()) lines.add(cleaned);
        }

        // Markup gave us nothing to go on — one unbroken run of prose. Fall back to sentences, so a
        // description typed as plain text still reads as points rather than as a paragraph wearing a
        // single bullet. Only when there is exactly ONE line: if the agent structured the text
        // themselves, that structure is the answer and re-splitting it would override their grouping.
        if (lines.size() == 1) return splitSentences(lines.get(0));
        return List.copyOf(lines);
    }

    /**
     * Abbreviations whose full stop does NOT end a sentence.
     *
     * <p>Without this, "Pickup by Mr. Sharma at 9 a.m. Transfer follows" splits after {@code Mr.}
     * and the reader gets a bullet that says only "Pickup by Mr." — worse than not splitting at all.
     * Deliberately a short list of what actually appears in travel copy rather than an attempt at
     * every English abbreviation: an unsplit sentence is a small cost, a mangled one is not.
     */
    private static final java.util.Set<String> ABBREVIATIONS = java.util.Set.of(
            "mr", "mrs", "ms", "dr", "st", "no", "jr", "sr", "vs", "etc", "approx",
            "a.m", "p.m", "am", "pm", "hrs", "ft", "no.");

    /**
     * Splits a run of prose into sentences, so plain-text descriptions still print as points.
     *
     * <p>The split point is a full stop, question or exclamation mark followed by whitespace and a
     * CAPITAL letter. That last condition is what keeps decimals and prices intact — "2.5 hours" and
     * "₹1,25,000.50" have no space after the stop, and "9.30 am" continues in lower case.
     *
     * <p>Author text is never edited: trailing punctuation stays exactly as typed. Trimming the
     * final full stop would look tidier in a bullet list and would also be this class quietly
     * rewriting what an agent wrote into a customer-facing document.
     */
    private List<String> splitSentences(String text) {
        String[] parts = text.split("(?<=[.!?])\\s+(?=[A-Z])");
        if (parts.length <= 1) return List.of(text);

        List<String> out = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        for (String part : parts) {
            if (pending.length() > 0) pending.append(' ');
            pending.append(part);
            // The split landed after an abbreviation — glue this piece to the next one instead.
            if (endsWithAbbreviation(pending.toString())) continue;
            out.add(pending.toString().trim());
            pending.setLength(0);
        }
        if (pending.length() > 0) out.add(pending.toString().trim());
        return List.copyOf(out);
    }

    private static boolean endsWithAbbreviation(String piece) {
        String trimmed = piece.trim();
        if (!trimmed.endsWith(".")) return false;
        String withoutDot = trimmed.substring(0, trimmed.length() - 1);
        int space = withoutDot.lastIndexOf(' ');
        String lastWord = (space < 0 ? withoutDot : withoutDot.substring(space + 1))
                .toLowerCase(Locale.ROOT);
        // A single letter is an initial ("J. Sharma"), never the end of a sentence.
        return lastWord.length() == 1 || ABBREVIATIONS.contains(lastWord);
    }

    /** Empty and whitespace-only both mean "absent" — the template hides on null, not on "". */
    public String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private String plural(int count, String noun) {
        return count + " " + (count == 1 ? noun : noun + "s");
    }
}

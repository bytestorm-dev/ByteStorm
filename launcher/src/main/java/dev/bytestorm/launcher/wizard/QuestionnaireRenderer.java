package dev.bytestorm.launcher.wizard;

import dev.bytestorm.cli.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Zeichnet den Fragebogen als Rahmen mit aufgeklappter Frage (§5.1).
 *
 * <p>Der Renderer ist eine reine Funktion von Zustand, Breite und Höhe: Er
 * verändert nichts und braucht kein Terminal. Damit lässt sich das Layout ohne
 * JLine prüfen — inklusive Umbruch, Höhenbegrenzung und ASCII-Ersatz.
 *
 * <p>Ist der Terminal schmal, entfallen die Seitenränder und die Hinweise werden
 * kürzer. Ohne Farben (oder ohne Unicode) erscheinen ASCII-Rahmen, {@code v}/{@code >}
 * und {@code (*)}/{@code ( )} — der Zustand bleibt in jedem Fall an Zeichen
 * ablesbar und hängt nie allein an der Farbe.
 */
final class QuestionnaireRenderer {

    static final int MIN_WIDTH = 20;
    static final int WIDE_WIDTH = 60;

    private QuestionnaireRenderer() {
    }

    /**
     * @param form   Fragebogen
     * @param state  aktueller Zustand
     * @param width  Terminalbreite in Zeichen
     * @param height Terminalhöhe in Zeilen
     * @return die Zeilen des Panels
     */
    static List<String> render(Questionnaire form, QuestionnaireState state, int width, int height) {
        int totalWidth = Math.max(MIN_WIDTH, width);
        boolean wide = totalWidth >= WIDE_WIDTH;
        int content = wide ? totalWidth - 2 : totalWidth;

        List<QuestionnaireState.Row> rows = state.rows();
        List<List<String>> blocks = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            List<String> lines = new ArrayList<>(
                    rowLines(form, state, rows.get(index), index == state.focusIndex(), content));
            // Eine Leerzeile trennt Fragen voneinander. Ohne sie klebt der nächste
            // Titel am letzten Options- oder Feldwert und das Formular wirkt gedrängt.
            if (index + 1 < rows.size() && startsSomethingNew(rows.get(index + 1))) {
                lines.add("");
            }
            blocks.add(lines);
        }

        List<String> bottomNotice = bottomNotice(state, content);
        List<String> subtitle = form.subtitle() == null
                ? List.of()
                : wrap(form.subtitle(), Math.max(4, content - 1));
        int focus = Math.min(state.focusIndex(), blocks.size() - 1);
        List<String> footer = footer(state, totalWidth, content, false);
        // Eine Leerzeile trennt den Inhalt von den Hinweisen.
        int chrome = 3 + footer.size() + subtitle.size() + bottomNotice.size();
        int capacity = Math.max(1, height - chrome);
        int start = windowStart(blocks, focus, capacity);
        if (hasMoreBelow(blocks, start, capacity)) {
            footer = footer(state, totalWidth, content, true);
            chrome = 3 + footer.size() + subtitle.size() + bottomNotice.size();
            capacity = Math.max(1, height - chrome);
            start = windowStart(blocks, focus, capacity);
        }
        List<String> body = new ArrayList<>();
        for (int index = start; index < blocks.size() && body.size() < capacity; index++) {
            for (String line : blocks.get(index)) {
                if (body.size() >= capacity) {
                    break;
                }
                body.add(line);
            }
        }

        if (height < 6) {
            return body;
        }

        List<String> lines = new ArrayList<>(body.size() + chrome);
        lines.add(topBorder(form.title() + "   " + position(state), totalWidth, wide));
        for (String line : subtitle) {
            lines.add(frame(Theme.dim(" " + line), totalWidth, wide, content));
        }
        for (String line : body) {
            lines.add(frame(line, totalWidth, wide, content));
        }
        for (String line : bottomNotice) {
            lines.add(frame(Theme.red(" " + line), totalWidth, wide, content));
        }
        lines.add(frame("", totalWidth, wide, content));
        for (String line : footer) {
            lines.add(frame(Theme.dim(" " + line), totalWidth, wide, content));
        }
        lines.add(bottomBorder(totalWidth, wide));
        return lines;
    }

    /**
     * Erzeugt die Zeilen einer Formularzeile. Eine Zeile ist immer auf die
     * Innenbreite aufgefüllt, damit die rechte Rahmenseite bündig steht.
     */
    static List<String> rowLines(Questionnaire form, QuestionnaireState state, QuestionnaireState.Row row,
                                 boolean focused, int content) {
        return switch (row.kind()) {
            case QUESTION -> questionLines(form, state, row, focused, content);
            case OPTION -> optionLines(form, state, row, focused, content);
            case FIELD -> fieldLines(form, state, row, focused, content);
            case SUBMIT -> List.of(focused
                    ? Theme.highlight(pad(submitLabel(), content))
                    : pad("  " + Theme.bold("[" + submitLabel() + "]"), content));
        };
    }

    private static List<String> questionLines(Questionnaire form, QuestionnaireState state,
                                              QuestionnaireState.Row row, boolean focused, int content) {
        Question question = form.question(row.questionId());
        boolean expanded = question.id().equals(state.expanded());
        String marker = expanded ? glyph("▼", "v") : glyph("▶", ">");
        int number = Math.max(1, state.questionNumber(question.id()));
        String prefix = number + ". ";
        String title = question.title() + (question.required() ? " " + Theme.red("*") : "");
        List<String> titleLines = wrap(prefix + title, Math.max(4, content - 3));

        List<String> lines = new ArrayList<>();
        String firstTitle = titleLines.get(0);
        if (focused) {
            lines.add(Theme.highlight(pad(" " + marker + " " + firstTitle, content)));
        } else {
            lines.add(pad(" " + Theme.accent(marker) + " " + Theme.bold(firstTitle), content));
        }
        for (int index = 1; index < titleLines.size(); index++) {
            lines.add(pad("   " + titleLines.get(index), content));
        }

        if (!expanded) {
            String summary = question.summary(state.answers());
            String arrow = glyph("↳", "->");
            String done = answered(question, state) ? Theme.green(glyph("✔", "+")) : Theme.dim(glyph("○", "o"));
            if (focused) {
                List<String> plain = wrap("   " + done + " " + arrow + " \"" + summary + "\"", content);
                for (String line : plain) {
                    lines.add(Theme.highlight(pad(line, content)));
                }
                return lines;
            }
            List<String> summaryLines = wrap(summary, Math.max(4, content - 10));
            for (int index = 0; index < summaryLines.size(); index++) {
                String summaryPrefix = index == 0 ? "   " + done + " " + arrow + " \"" : "       ";
                String suffix = index == summaryLines.size() - 1 ? "\"" : "";
                lines.add(pad(summaryPrefix + Theme.accent(summaryLines.get(index)) + suffix, content));
            }
            return lines;
        }

        if (question.description() != null) {
            for (String line : wrap(question.description(), Math.max(4, content - 4))) {
                lines.add(pad("   " + Theme.dim(line), content));
            }
        }
        if (state.notice().present() && question.id().equals(state.notice().questionId())) {
            for (String line : wrap(state.notice().message(), Math.max(4, content - 6))) {
                lines.add(pad("   " + Theme.red(glyph("✖ ", "x ") + line), content));
            }
        }
        return lines;
    }

    private static List<String> optionLines(Questionnaire form, QuestionnaireState state,
                                            QuestionnaireState.Row row, boolean focused, int content) {
        Question question = form.question(row.questionId());
        QuestionOption option = question.options().get(row.optionIndex());
        boolean selected = question.isSelected(state.answers(), option);
        String marker = selected ? glyph("◉", "(*)") : glyph("○", "( )");
        String plain = "   " + marker + " " + option.label();

        List<String> lines = new ArrayList<>();
        if (focused) {
            lines.add(Theme.highlight(pad(plain, content)));
        } else if (selected) {
            lines.add(pad("   " + Theme.accent(marker) + " " + option.label(), content));
        } else {
            lines.add(pad("   " + Theme.magenta(marker) + " " + option.label(), content));
        }
        if (option.description() != null) {
            for (String line : wrap(option.description(), Math.max(4, content - 6))) {
                lines.add(pad("     " + Theme.dim(line), content));
            }
        }
        return lines;
    }

    private static List<String> fieldLines(Questionnaire form, QuestionnaireState state,
                                           QuestionnaireState.Row row, boolean focused, int content) {
        Question question = form.question(row.questionId());
        String value = state.answers().text(question.id());
        boolean empty = value.isBlank();
        String shown;
        if (question.type() == QuestionType.SECRET) {
            shown = empty ? "(leer)" : "*".repeat(Math.min(value.length(), 24));
        } else if (empty) {
            shown = question.defaultValue() == null || question.defaultValue().isBlank()
                    ? "(leer)"
                    : question.defaultValue();
        } else {
            shown = value;
        }
        String cursor = focused ? glyph("▌", "_") : "";
        String plain = "   " + shown + cursor;
        if (focused) {
            return List.of(Theme.highlight(pad(plain, content)));
        }
        return List.of(pad("   " + (empty ? Theme.dim(shown) : Theme.accent(shown)), content));
    }

    /**
     * @param row folgende Formularzeile
     * @return {@code true}, wenn dort eine neue Frage oder die Schaltfläche beginnt
     */
    private static boolean startsSomethingNew(QuestionnaireState.Row row) {
        return row.kind() == QuestionnaireState.Kind.QUESTION
                || row.kind() == QuestionnaireState.Kind.SUBMIT;
    }

    /**
     * @param blocks   Zeilen je Formularzeile
     * @param start    erste angezeigte Formularzeile
     * @param capacity verfügbare Zeilen
     * @return {@code true}, wenn unterhalb des sichtbaren Bereichs noch Zeilen folgen
     */
    static boolean hasMoreBelow(List<List<String>> blocks, int start, int capacity) {
        int used = 0;
        for (int index = start; index < blocks.size(); index++) {
            int size = blocks.get(index).size();
            if (used + size > capacity) {
                return true;
            }
            used += size;
        }
        return false;
    }

    /**
     * @param blocks  Zeilen je Formularzeile
     * @param focus   fokussierte Zeile
     * @param capacity verfügbare Zeilen
     * @return erste anzuzeigende Formularzeile, sodass der Fokus sichtbar bleibt
     */
    static int windowStart(List<List<String>> blocks, int focus, int capacity) {
        int start = 0;
        while (start < focus) {
            int used = 0;
            for (int index = start; index <= focus; index++) {
                used += blocks.get(index).size();
            }
            if (used <= capacity) {
                break;
            }
            start++;
        }
        return start;
    }

    /**
     * @param state    Zustand
     * @param width    Terminalbreite
     * @param content verfügbare Innenbreite
     * @return Hinweiszeilen für die Fußzeile
     */
    static List<String> footer(QuestionnaireState state, int width, int content, boolean more) {
        String hints;
        if (width < WIDE_WIDTH) {
            hints = Theme.unicode()
                    ? "↑↓ · Enter · Esc"
                    : "Pfeiltasten · Enter · Esc";
        } else if (state.editingText()) {
            hints = "Tippen ändert den Wert · Enter weiter · Esc Abbrechen";
        } else {
            hints = "↑↓ Navigieren · Enter Auswählen · ←→ Ein-/Ausklappen · Esc Abbrechen";
        }
        if (more) {
            hints = hints + (Theme.unicode() ? "  ·  ↓ mehr" : "  ·  v mehr");
        }
        // Die Zeile bekommt vorne ein Leerzeichen als Rand, daher content - 1.
        return wrap(hints, Math.max(8, content - 1));
    }

    private static List<String> bottomNotice(QuestionnaireState state, int content) {
        QuestionnaireNotice notice = state.notice();
        if (!notice.present()) {
            return List.of();
        }
        boolean inline = notice.questionId() != null
                && state.form().contains(notice.questionId())
                && state.form().question(notice.questionId()).visible(state.answers());
        if (inline) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : wrap(notice.message(), Math.max(4, content - 4))) {
            lines.add(" " + glyph("✖ ", "x ") + line);
        }
        return lines;
    }

    /**
     * @param question Frage
     * @param state    Zustand
     * @return {@code true}, wenn die Frage schon beantwortet wurde
     */
    private static boolean answered(Question question, QuestionnaireState state) {
        return switch (question.type()) {
            case MULTI_SELECT -> !state.answers().all(question.id()).isEmpty();
            default -> !state.answers().text(question.id()).isBlank();
        };
    }

    /**
     * @param state Zustand
     * @return Position im Formular, z. B. {@code 3 / 14}
     */
    private static String position(QuestionnaireState state) {
        long visible = state.form().questions().stream()
                .filter(question -> question.visible(state.answers()))
                .count();
        if (visible <= 0 || state.rows().isEmpty()) {
            return "";
        }
        QuestionnaireState.Row row = state.rows().get(Math.min(state.focusIndex(), state.rows().size() - 1));
        int number = row.questionId() == null ? 0 : state.questionNumber(row.questionId());
        if (number <= 0) {
            number = (int) visible;
        }
        return number + " / " + visible;
    }

    private static String submitLabel() {
        return "Speichern";
    }

    private static String topBorder(String title, int width, boolean wide) {
        String horizontal = glyph("─", "-");
        String clipped = clip(title, Math.max(4, width - 6));
        String label = " " + clipped + " ";
        if (!wide) {
            int fill = Math.max(0, width - label.length());
            return Theme.accent(horizontal.repeat(Math.min(fill, 2)) + label + horizontal.repeat(Math.max(0, fill - Math.min(fill, 2))));
        }
        int fill = Math.max(0, width - 2 - label.length());
        int left = fill / 2;
        int right = fill - left;
        return Theme.accent(glyph("┌", "+") + horizontal.repeat(left) + label + horizontal.repeat(right) + glyph("┐", "+"));
    }

    private static String bottomBorder(int width, boolean wide) {
        String horizontal = glyph("─", "-");
        if (!wide) {
            return Theme.accent(horizontal.repeat(width));
        }
        return Theme.accent(glyph("└", "+") + horizontal.repeat(Math.max(0, width - 2)) + glyph("┘", "+"));
    }

    private static String frame(String line, int width, boolean wide, int content) {
        String padded = pad(line, content);
        if (!wide) {
            return padded;
        }
        String edge = Theme.accent(glyph("│", "|"));
        return edge + padded + edge;
    }

    /**
     * @param styled bereits gefärbter Text
     * @param width  Zielbreite
     * @return Text, aufgefüllt auf die sichtbare Breite
     */
    static String pad(String styled, int width) {
        int visible = Theme.stripAnsi(styled).length();
        return visible >= width ? styled : styled + " ".repeat(width - visible);
    }

    /**
     * Bricht Text auf die angegebene Breite um. Wörter, die länger als die Zeile
     * sind, werden hart getrennt, damit keine Zeile über den Rahmen läuft.
     *
     * @param text  Text
     * @param width maximale Zeilenbreite
     * @return Zeilen, mindestens eine
     */
    static List<String> wrap(String text, int width) {
        int limit = Math.max(1, width);
        List<String> lines = new ArrayList<>();
        String source = text == null ? "" : text;
        for (String paragraph : source.split("\\R", -1)) {
            String remaining = paragraph.strip();
            if (remaining.isEmpty()) {
                lines.add("");
                continue;
            }
            while (remaining.length() > limit) {
                int cut = remaining.lastIndexOf(' ', limit);
                if (cut <= 0) {
                    cut = limit;
                }
                lines.add(remaining.substring(0, cut).stripTrailing());
                remaining = remaining.substring(cut).stripLeading();
            }
            lines.add(remaining);
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    private static String clip(String text, int width) {
        if (text.length() <= width) {
            return text;
        }
        String ellipsis = Theme.unicode() ? "…" : "...";
        return text.substring(0, Math.max(1, width - ellipsis.length())) + ellipsis;
    }

    private static String glyph(String unicode, String ascii) {
        return Theme.unicode() ? unicode : ascii;
    }
}

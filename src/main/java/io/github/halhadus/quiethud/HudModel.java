package io.github.halhadus.quiethud;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

final class HudModel {
    record Row(String text, int color, int width) {}
    record Frame(List<Row> rows, int width, int height) {}
    static boolean visible(Config c, Config.Entry e, Sampler.Value value) {
        return e.enabled && !(c.hideUnavailable && Config.automaticSensor(e) && !value.error().isBlank());
    }
    static Frame build(Config c, Sampler sampler, int lineHeight, ToIntFunction<String> measure) {
        List<Row> rows = new ArrayList<>(); int width = 0;
        for (Config.Entry e : c.entries) {
            if (!e.enabled) continue;
            Sampler.Value value = sampler.value(e);
            if (!visible(c, e, value)) continue;
            String text = (e.label.isBlank() ? "" : e.label + ": ") + value.text();
            int rowWidth = measure.applyAsInt(text); width = Math.max(width, rowWidth + 4);
            rows.add(new Row(text, Integer.parseInt(e.color, 16), rowWidth));
        }
        return new Frame(List.copyOf(rows), Math.max(1, width), Math.max(1, rows.size() * (lineHeight + c.spacing)));
    }
}

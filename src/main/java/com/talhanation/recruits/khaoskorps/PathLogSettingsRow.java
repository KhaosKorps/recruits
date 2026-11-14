package com.talhanation.recruits.khaoskorps;

import java.lang.reflect.RecordComponent;

/**
 * The path log's settings file: one row when logging starts and one each time a setting changes,
 * numbered by settings_v, which every path row carries. The columns are every FollowSettings
 * component in declaration order, as snake_case, then the two upstream settings that decide where
 * searches run. Booleans are 0 or 1, decimals use Double.toString (a point in every locale), and
 * choices are their names. Plain Java, covered by JUnit.
 */
public final class PathLogSettingsRow {

    private static final RecordComponent[] COMPONENTS = FollowSettings.class.getRecordComponents();

    public static final String HEADER = header();

    private PathLogSettingsRow() {
    }

    public static String row(int settingsVersion, long tick, FollowSettings settings, boolean useAsync, int asyncThreads) {
        StringBuilder row = new StringBuilder().append(settingsVersion).append(',').append(tick);
        for (RecordComponent component : COMPONENTS) {
            row.append(',').append(value(component, settings));
        }
        return row.append(',').append(useAsync ? 1 : 0).append(',').append(asyncThreads).toString();
    }

    private static String header() {
        StringBuilder header = new StringBuilder("settings_v,tick");
        for (RecordComponent component : COMPONENTS) header.append(',').append(snakeCase(component.getName()));
        return header.append(",use_async_pathfinding,async_pathfinding_threads").toString();
    }

    private static String value(RecordComponent component, FollowSettings settings) {
        Object value;
        try {
            value = component.getAccessor().invoke(settings);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't read FollowSettings." + component.getName(), e);
        }
        if (value instanceof Boolean flag) return flag ? "1" : "0";
        if (value instanceof Enum<?> choice) return choice.name();
        return String.valueOf(value);
    }

    static String snakeCase(String camelCase) {
        StringBuilder out = new StringBuilder();
        for (char c : camelCase.toCharArray()) {
            if (Character.isUpperCase(c)) out.append('_').append(Character.toLowerCase(c));
            else out.append(c);
        }
        return out.toString();
    }
}

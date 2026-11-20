package io.github.freeform.bubble;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内存中的 SharedPreferences 实现，用于把 ContentProvider 返回的 Bundle
 * 转换为 BubbleConfig 可直接使用的 SharedPreferences。
 */
final class InMemorySharedPreferences implements SharedPreferences {
    private final Map<String, Object> values = new HashMap<>();

    void putBoolean(String key, boolean value) {
        values.put(key, value);
    }

    void putInt(String key, int value) {
        values.put(key, value);
    }

    void putString(String key, String value) {
        values.put(key, value);
    }

    void putLong(String key, long value) {
        values.put(key, value);
    }

    void putFloat(String key, float value) {
        values.put(key, value);
    }

    void putStringSet(String key, Set<String> value) {
        values.put(key, new HashSet<>(value));
    }

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<>(values);
    }

    @Override
    public String getString(String key, String defValue) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : defValue;
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        Object value = values.get(key);
        if (value instanceof Set) {
            @SuppressWarnings("unchecked")
            Set<String> set = (Set<String>) value;
            return new HashSet<>(set);
        }
        return defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        Object value = values.get(key);
        return value instanceof Integer ? (int) value : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        Object value = values.get(key);
        return value instanceof Long ? (long) value : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        Object value = values.get(key);
        return value instanceof Float ? (float) value : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object value = values.get(key);
        return value instanceof Boolean ? (boolean) value : defValue;
    }

    @Override
    public boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override
    public Editor edit() {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
    }

    static InMemorySharedPreferences fromBundle(android.os.Bundle bundle) {
        InMemorySharedPreferences prefs = new InMemorySharedPreferences();
        if (bundle == null) return prefs;
        // The provider ships the preference store in a dedicated nested bundle so the
        // transport flags ("success", legacy aliases, ...) cannot be mistaken for user
        // settings. Fall back to the flat layout for compatibility.
        android.os.Bundle source = bundle.getBundle(BubbleConfigProvider.EXTRA_PREFERENCES);
        if (source == null) source = bundle;
        for (String key : source.keySet()) {
            Object value = source.get(key);
            if (value instanceof Boolean) {
                prefs.putBoolean(key, (boolean) value);
            } else if (value instanceof Integer) {
                prefs.putInt(key, (int) value);
            } else if (value instanceof Long) {
                prefs.putLong(key, (long) value);
            } else if (value instanceof Float) {
                prefs.putFloat(key, (float) value);
            } else if (value instanceof String) {
                prefs.putString(key, (String) value);
            } else if (value instanceof String[]) {
                prefs.putStringSet(key, new HashSet<>(java.util.Arrays.asList((String[]) value)));
            } else if (value instanceof ArrayList && !((ArrayList<?>) value).isEmpty()
                    && ((ArrayList<?>) value).get(0) instanceof String) {
                @SuppressWarnings("unchecked")
                Set<String> set = new HashSet<>((ArrayList<String>) value);
                prefs.putStringSet(key, set);
            } else if (value instanceof ArrayList && ((ArrayList<?>) value).isEmpty()) {
                // An explicitly empty app list means "no app selected"; keep the key so the
                // reader does not confuse it with a value that was never persisted.
                prefs.putStringSet(key, new HashSet<>());
            }
        }
        return prefs;
    }
}

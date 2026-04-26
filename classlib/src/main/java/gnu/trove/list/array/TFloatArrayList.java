package gnu.trove.list.array;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Random;

/**
 * TeaVM-compatible replacement for Trove's TFloatArrayList.
 * Extends ArrayList&lt;Float&gt; instead of using sun.misc.Unsafe-backed primitive storage.
 * Provides the full Trove TFloatArrayList API via delegation to ArrayList methods.
 */
public class TFloatArrayList extends ArrayList<Float> {

    protected Float no_entry_value;

    public TFloatArrayList() { super(); no_entry_value = 0f; }
    public TFloatArrayList(int capacity) { super(capacity); no_entry_value = 0f; }
    public TFloatArrayList(Collection<? extends Float> c) { super(c); no_entry_value = 0f; }
    public TFloatArrayList(float[] values) {
        super(values.length);
        for (float v : values) add(v);
    }

    public Float getNoEntryValue() { return no_entry_value; }
    public void setNoEntryValue(Float v) { no_entry_value = v; }

    public float getQuick(int index) {
        if (index < 0 || index >= size()) return no_entry_value;
        return get(index);
    }

    public void setQuick(int index, float value) {
        if (index < 0) return;
        if (index >= size()) {
            while (size() <= index) add(no_entry_value);
        }
        set(index, value);
    }

    public void insert(int index, float value) {
        add(index, value);
    }

    public float removeAt(int index) {
        return remove(index);
    }

    public void ensureCapacity(int capacity) {
        super.ensureCapacity(capacity);
    }

    public int binarySearch(float value) {
        return Collections.binarySearch(this, value);
    }

    public void fill(float value) {
        for (int i = 0; i < size(); i++) set(i, value);
    }

    public void reverse() {
        for (int i = 0, j = size() - 1; i < j; i++, j--) {
            float tmp = get(i);
            set(i, get(j));
            set(j, tmp);
        }
    }

    public void shuffle(Random random) {
        for (int i = size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            float tmp = get(i);
            set(i, get(j));
            set(j, tmp);
        }
    }

    public float max() {
        if (isEmpty()) throw new IllegalStateException();
        float m = get(0);
        for (int i = 1; i < size(); i++) { float v = get(i); if (v > m) m = v; }
        return m;
    }

    public float min() {
        if (isEmpty()) throw new IllegalStateException();
        float m = get(0);
        for (int i = 1; i < size(); i++) { float v = get(i); if (v < m) m = v; }
        return m;
    }

    public float sum() {
        float s = 0;
        for (int i = 0; i < size(); i++) s += get(i);
        return s;
    }
}

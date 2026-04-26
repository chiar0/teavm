package gnu.trove.list.array;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Random;

/**
 * TeaVM-compatible replacement for Trove's TLongArrayList.
 * Extends ArrayList<Long> instead of using sun.misc.Unsafe-backed primitive storage.
 * Provides the full Trove TLongArrayList API via delegation to ArrayList methods.
 */
public class TLongArrayList extends ArrayList<Long> {

    protected long no_entry_value;

    public TLongArrayList() { super(); no_entry_value = 0L; }
    public TLongArrayList(int capacity) { super(capacity); no_entry_value = 0L; }
    public TLongArrayList(Collection<? extends Long> c) { super(c); no_entry_value = 0L; }
    public TLongArrayList(long[] values) { 
        super(values.length);
        for (long v : values) add(v);
    }

    public long getNoEntryValue() { return no_entry_value; }
    public void setNoEntryValue(long v) { no_entry_value = v; }

    public long getQuick(int index) {
        if (index < 0 || index >= size()) return no_entry_value;
        return get(index);
    }

    public void setQuick(int index, long value) {
        if (index < 0) return;
        if (index >= size()) {
            while (size() <= index) add(no_entry_value);
        }
        set(index, value);
    }

    public void insert(int index, long value) {
        add(index, value);
    }

    public long removeAt(int index) {
        return remove(index);
    }

    public void ensureCapacity(int capacity) {
        super.ensureCapacity(capacity);
    }

    public int binarySearch(long value) {
        return Collections.binarySearch(this, value);
    }

    public void fill(long value) {
        for (int i = 0; i < size(); i++) set(i, value);
    }

    public void reverse() {
        for (int i = 0, j = size() - 1; i < j; i++, j--) {
            long tmp = get(i);
            set(i, get(j));
            set(j, tmp);
        }
    }

    public void sort() {
        this.sort(null);
    }

    public void shuffle(Random random) {
        for (int i = size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            long tmp = get(i);
            set(i, get(j));
            set(j, tmp);
        }
    }

    public long[] toArray(long[] dest) {
        if (dest.length < size()) dest = new long[size()];
        for (int i = 0; i < size(); i++) dest[i] = get(i);
        return dest;
    }

    public long max() {
        if (isEmpty()) throw new IllegalStateException();
        long m = get(0);
        for (int i = 1; i < size(); i++) { long v = get(i); if (v > m) m = v; }
        return m;
    }

    public long min() {
        if (isEmpty()) throw new IllegalStateException();
        long m = get(0);
        for (int i = 1; i < size(); i++) { long v = get(i); if (v < m) m = v; }
        return m;
    }

    public long sum() {
        long s = 0L;
        for (int i = 0; i < size(); i++) s += get(i);
        return s;
    }
}

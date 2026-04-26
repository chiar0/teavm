package gnu.trove.list.array;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Random;

/**
 * TeaVM-compatible replacement for Trove's TIntArrayList.
 * Extends ArrayList<Integer> instead of using sun.misc.Unsafe-backed primitive storage.
 * Provides the full Trove TIntArrayList API via delegation to ArrayList methods.
 */
public class TIntArrayList extends ArrayList<Integer> {

    protected int no_entry_value;

    public TIntArrayList() { super(); no_entry_value = 0; }
    public TIntArrayList(int capacity) { super(capacity); no_entry_value = 0; }
    public TIntArrayList(Collection<? extends Integer> c) { super(c); no_entry_value = 0; }
    public TIntArrayList(int[] values) { 
        super(values.length);
        for (int v : values) add(v);
    }

    public int getNoEntryValue() { return no_entry_value; }
    public void setNoEntryValue(int v) { no_entry_value = v; }

    public int getQuick(int index) {
        if (index < 0 || index >= size()) return no_entry_value;
        return get(index);
    }

    public void setQuick(int index, int value) {
        if (index < 0) return;
        if (index >= size()) {
            while (size() <= index) add(no_entry_value);
        }
        set(index, value);
    }

    public void insert(int index, int value) {
        add(index, value);
    }

    public int removeAt(int index) {
        return remove(index);
    }

    public void ensureCapacity(int capacity) {
        super.ensureCapacity(capacity);
    }

    public int binarySearch(int value) {
        return Collections.binarySearch(this, value);
    }

    public void fill(int value) {
        for (int i = 0; i < size(); i++) set(i, value);
    }

    public void reverse() {
        for (int i = 0, j = size() - 1; i < j; i++, j--) {
            int tmp = get(i);
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
            int tmp = get(i);
            set(i, get(j));
            set(j, tmp);
        }
    }

    public int[] toArray(int[] dest) {
        if (dest.length < size()) dest = new int[size()];
        for (int i = 0; i < size(); i++) dest[i] = get(i);
        return dest;
    }

    public int max() {
        if (isEmpty()) throw new IllegalStateException();
        int m = get(0);
        for (int i = 1; i < size(); i++) { int v = get(i); if (v > m) m = v; }
        return m;
    }

    public int min() {
        if (isEmpty()) throw new IllegalStateException();
        int m = get(0);
        for (int i = 1; i < size(); i++) { int v = get(i); if (v < m) m = v; }
        return m;
    }

    public int sum() {
        int s = 0;
        for (int i = 0; i < size(); i++) s += get(i);
        return s;
    }
}

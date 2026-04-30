package gnu.trove.list.array;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Random;

import gnu.trove.iterator.TFloatIterator;

/**
 * TeaVM-compatible replacement for Trove's TFloatArrayList.
 * Uses internal float[] storage instead of sun.misc.Unsafe.
 */
public class TFloatArrayList {

    protected float no_entry_value;
    private float[] data;
    private int size;

    private static final int DEFAULT_CAPACITY = 10;

    public TFloatArrayList() { this(DEFAULT_CAPACITY); }
    public TFloatArrayList(int capacity) { data = new float[Math.max(1, capacity)]; size = 0; }
    public TFloatArrayList(Collection<? extends Float> c) {
        this(c.size());
        if (c instanceof ArrayList) {
            ArrayList<? extends Float> al = (ArrayList<? extends Float>) c;
            for (int i = 0; i < al.size(); i++) {
                add(al.get(i).floatValue());
            }
        } else {
            for (Float v : c) add(v.floatValue());
        }
    }
    public TFloatArrayList(float[] values) { data = values.clone(); size = values.length; }
    public TFloatArrayList(TFloatArrayList other) {
        data = new float[Math.max(1, other.size)];
        System.arraycopy(other.data, 0, data, 0, other.size);
        size = other.size;
        no_entry_value = other.no_entry_value;
    }

    public float getNoEntryValue() { return no_entry_value; }
    public void setNoEntryValue(float v) { no_entry_value = v; }

    private void grow(int minCapacity) {
        if (minCapacity <= data.length) return;
        int newCap = Math.max(data.length + (data.length >> 1), minCapacity);
        float[] nd = new float[newCap];
        System.arraycopy(data, 0, nd, 0, size);
        data = nd;
    }

    // ---- Primitive API ----

    public boolean add(float val) {
        grow(size + 1);
        data[size++] = val;
        return true;
    }

    public float get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        return data[index];
    }

    public float set(int index, float val) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        float old = data[index];
        data[index] = val;
        return old;
    }

    public boolean contains(float val) {
        return indexOf(val) >= 0;
    }

    public int indexOf(float val) {
        for (int i = 0; i < size; i++) if (Float.compare(data[i], val) == 0) return i;
        return -1;
    }

    public boolean remove(float val) {
        int idx = indexOf(val);
        if (idx < 0) return false;
        removeAt(idx);
        return true;
    }

    public float removeAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        float old = data[index];
        int moved = size - index - 1;
        if (moved > 0) System.arraycopy(data, index + 1, data, index, moved);
        size--;
        return old;
    }

    public void reset() { clear(); }

    public float[] toArray() {
        float[] result = new float[size];
        System.arraycopy(data, 0, result, 0, size);
        return result;
    }

    public float[] toArray(float[] dest) {
        if (dest.length < size) dest = new float[size];
        System.arraycopy(data, 0, dest, 0, size);
        return dest;
    }

    public static TFloatArrayList wrap(float[] a) {
        TFloatArrayList list = new TFloatArrayList(0);
        list.data = a;
        list.size = a.length;
        return list;
    }

    // ---- Boxed overloads for generic interop ----

    public int size() { return size; }
    public Float get(Integer index) { return get(index.intValue()); }
    public boolean add(Float val) { return add(val.floatValue()); }
    public Float set(Integer index, Float val) { return set(index.intValue(), val.floatValue()); }
    public Float remove(Integer index) { return removeAt(index.intValue()); }
    public void clear() { size = 0; }
    public boolean isEmpty() { return size == 0; }

    // ---- Additional API ----

    public float getQuick(int index) {
        if (index < 0 || index >= size) return no_entry_value;
        return data[index];
    }

    public void setQuick(int index, float value) {
        if (index < 0) return;
        grow(index + 1);
        data[index] = value;
        if (index >= size) size = index + 1;
    }

    public void insert(int index, float value) {
        if (index < 0 || index > size) throw new IndexOutOfBoundsException();
        grow(size + 1);
        System.arraycopy(data, index, data, index + 1, size - index);
        data[index] = value;
        size++;
    }

    public void ensureCapacity(int capacity) { grow(capacity); }

    public void fill(float value) {
        for (int i = 0; i < size; i++) data[i] = value;
    }

    public void reverse() {
        for (int i = 0, j = size - 1; i < j; i++, j--) {
            float tmp = data[i]; data[i] = data[j]; data[j] = tmp;
        }
    }

    public void sort() { java.util.Arrays.sort(data, 0, size); }

    public void shuffle(Random random) {
        for (int i = size - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            float tmp = data[i]; data[i] = data[j]; data[j] = tmp;
        }
    }

    public float max() {
        if (size == 0) throw new IllegalStateException();
        float m = data[0];
        for (int i = 1; i < size; i++) if (data[i] > m) m = data[i];
        return m;
    }

    public float min() {
        if (size == 0) throw new IllegalStateException();
        float m = data[0];
        for (int i = 1; i < size; i++) if (data[i] < m) m = data[i];
        return m;
    }

    public float sum() {
        float s = 0;
        for (int i = 0; i < size; i++) s += data[i];
        return s;
    }

    public TFloatIterator iterator() {
        return new TFloatArrayIterator();
    }

    class TFloatArrayIterator implements TFloatIterator {
        private int _index;
        private int _lastReturned = -1;

        public boolean hasNext() {
            return _index < size;
        }

        public float next() {
            if (!hasNext()) throw new IndexOutOfBoundsException();
            _lastReturned = _index;
            return data[_index++];
        }

        public void remove() {
            if (_lastReturned < 0) throw new IllegalStateException();
            int idx = _lastReturned;
            _index--;
            _lastReturned = -1;
            int moved = size - idx - 1;
            if (moved > 0) System.arraycopy(data, idx + 1, data, idx, moved);
            size--;
        }
    }
}

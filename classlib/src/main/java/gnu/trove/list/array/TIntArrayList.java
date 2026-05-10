package gnu.trove.list.array;

import java.util.Random;

import gnu.trove.TIntCollection;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.procedure.TIntProcedure;

/**
 * TeaVM-compatible replacement for Trove's TIntArrayList.
 * Uses internal int[] storage instead of sun.misc.Unsafe.
 */
public class TIntArrayList implements TIntCollection, java.io.Externalizable {

    protected int no_entry_value;
    private int[] data;
    private int size;

    private static final int DEFAULT_CAPACITY = 10;

    public TIntArrayList() { this(DEFAULT_CAPACITY); }
    public TIntArrayList(int capacity) { data = new int[Math.max(1, capacity)]; size = 0; }
    public TIntArrayList(int[] values) { data = values.clone(); size = values.length; }
    public TIntArrayList(TIntArrayList other) {
        data = new int[Math.max(1, other.size)];
        System.arraycopy(other.data, 0, data, 0, other.size);
        size = other.size;
        no_entry_value = other.no_entry_value;
    }
    public TIntArrayList(TIntCollection c) {
        this(c.size());
        if (c instanceof TIntArrayList) {
            TIntArrayList other = (TIntArrayList) c;
            System.arraycopy(other.data, 0, data, 0, other.size);
            size = other.size;
        } else {
            TIntIterator it = c.iterator();
            while (it.hasNext()) {
                add(it.next());
            }
        }
    }

    public int getNoEntryValue() { return no_entry_value; }
    public void setNoEntryValue(int v) { no_entry_value = v; }

    private void grow(int minCapacity) {
        if (minCapacity <= data.length) return;
        int newCap = Math.max(data.length + (data.length >> 1), minCapacity);
        int[] nd = new int[newCap];
        System.arraycopy(data, 0, nd, 0, size);
        data = nd;
    }

    // ---- Primitive API (exact Trove signatures) ----

    public boolean add(int val) {
        grow(size + 1);
        data[size++] = val;
        return true;
    }

    public void add(int[] vals) {
        grow(size + vals.length);
        System.arraycopy(vals, 0, data, size, vals.length);
        size += vals.length;
    }

    public int get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        return data[index];
    }

    public int set(int index, int val) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        int old = data[index];
        data[index] = val;
        return old;
    }

    public boolean contains(int val) {
        return indexOf(val) >= 0;
    }

    public int indexOf(int val) {
        for (int i = 0; i < size; i++) if (data[i] == val) return i;
        return -1;
    }

    public boolean remove(int val) {
        int idx = indexOf(val);
        if (idx < 0) return false;
        removeAt(idx);
        return true;
    }

    public int removeAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        int old = data[index];
        int moved = size - index - 1;
        if (moved > 0) System.arraycopy(data, index + 1, data, index, moved);
        size--;
        return old;
    }

    public void insert(int index, int value) {
        if (index < 0 || index > size) throw new IndexOutOfBoundsException();
        grow(size + 1);
        System.arraycopy(data, index, data, index + 1, size - index);
        data[index] = value;
        size++;
    }

    public void reset() { clear(); }

    public int[] toArray() {
        int[] result = new int[size];
        System.arraycopy(data, 0, result, 0, size);
        return result;
    }

    public int[] toArray(int[] dest) {
        if (dest.length < size) dest = new int[size];
        System.arraycopy(data, 0, dest, 0, size);
        return dest;
    }

    public int[] toArray(int[] dest, int offset, int len) {
        return toArray(dest, offset, len, 0);
    }

    public int[] toArray(int[] dest, int offset, int len, int srcPos) {
        if (dest.length < size) dest = new int[size];
        int limit = Math.min(len, size - srcPos);
        System.arraycopy(data, srcPos, dest, offset, limit);
        return dest;
    }

    public static TIntArrayList wrap(int[] a) {
        TIntArrayList list = new TIntArrayList(0);
        list.data = a;
        list.size = a.length;
        return list;
    }

    public boolean addAll(TIntCollection c) {
        if (c instanceof TIntArrayList) {
            TIntArrayList other = (TIntArrayList) c;
            grow(size + other.size);
            System.arraycopy(other.data, 0, data, size, other.size);
            size += other.size;
            return other.size > 0;
        }
        boolean modified = false;
        for (gnu.trove.iterator.TIntIterator it = c.iterator(); it.hasNext(); ) {
            add(it.next());
            modified = true;
        }
        return modified;
    }

    public boolean removeAll(TIntCollection c) {
        if (c instanceof TIntArrayList) {
            TIntArrayList other = (TIntArrayList) c;
            boolean modified = false;
            for (int i = 0; i < other.size; i++) {
                if (remove(other.data[i])) modified = true;
            }
            return modified;
        }
        boolean modified = false;
        for (gnu.trove.iterator.TIntIterator it = c.iterator(); it.hasNext(); ) {
            if (remove(it.next())) modified = true;
        }
        return modified;
    }

    // ---- Boxed overloads for generic interop ----

    public int size() { return size; }

    public Integer get(Integer index) { return get(index.intValue()); }

    public boolean add(Integer val) { return add(val.intValue()); }

    public Integer set(Integer index, Integer val) { return set(index.intValue(), val.intValue()); }

    public Integer remove(Integer index) { return removeAt(index.intValue()); }

    public void clear() { size = 0; }

    public boolean isEmpty() { return size == 0; }

    // ---- Additional Trove API ----

    public int getQuick(int index) {
        if (index < 0 || index >= size) return no_entry_value;
        return data[index];
    }

    public void setQuick(int index, int value) {
        if (index < 0) return;
        grow(index + 1);
        data[index] = value;
        if (index >= size) size = index + 1;
    }

    public void ensureCapacity(int capacity) { grow(capacity); }

    public void trimToSize() {
        if (size < data.length) {
            int[] nd = new int[size];
            System.arraycopy(data, 0, nd, 0, size);
            data = nd;
        }
    }

    public int binarySearch(int value) {
        int lo = 0, hi = size - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (data[mid] < value) lo = mid + 1;
            else if (data[mid] > value) hi = mid - 1;
            else return mid;
        }
        return -(lo + 1);
    }

    public void fill(int value) {
        for (int i = 0; i < size; i++) data[i] = value;
    }

    public void reverse() {
        for (int i = 0, j = size - 1; i < j; i++, j--) {
            int tmp = data[i]; data[i] = data[j]; data[j] = tmp;
        }
    }

    public void sort() {
        java.util.Arrays.sort(data, 0, size);
    }

    public void shuffle(Random random) {
        for (int i = size - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = data[i]; data[i] = data[j]; data[j] = tmp;
        }
    }

    public int max() {
        if (size == 0) throw new IllegalStateException();
        int m = data[0];
        for (int i = 1; i < size; i++) if (data[i] > m) m = data[i];
        return m;
    }

    public int min() {
        if (size == 0) throw new IllegalStateException();
        int m = data[0];
        for (int i = 1; i < size; i++) if (data[i] < m) m = data[i];
        return m;
    }

    public int sum() {
        int s = 0;
        for (int i = 0; i < size; i++) s += data[i];
        return s;
    }

    public TIntIterator iterator() {
        return new TIntArrayIterator(this);
    }

    public boolean containsAll(TIntCollection c) {
        if (c instanceof TIntArrayList) {
            TIntArrayList other = (TIntArrayList) c;
            for (int i = 0; i < other.size; i++) {
                if (!contains(other.data[i])) return false;
            }
            return true;
        }
        TIntIterator it = c.iterator();
        while (it.hasNext()) if (!contains(it.next())) return false;
        return true;
    }

    public boolean containsAll(java.util.Collection<?> c) {
        for (Object o : c) if (!contains(((Integer) o).intValue())) return false;
        return true;
    }

    public boolean containsAll(int[] a) {
        for (int v : a) if (!contains(v)) return false;
        return true;
    }

    public boolean addAll(java.util.Collection<? extends Integer> c) {
        boolean modified = false;
        for (Integer i : c) { add(i.intValue()); modified = true; }
        return modified;
    }

    public boolean addAll(int[] a) {
        add(a); return true;
    }

    public boolean retainAll(java.util.Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    public boolean retainAll(TIntCollection c) {
        throw new UnsupportedOperationException();
    }

    public boolean retainAll(int[] a) {
        throw new UnsupportedOperationException();
    }

    public boolean removeAll(java.util.Collection<?> c) {
        boolean modified = false;
        for (Object o : c) if (remove(((Integer) o).intValue())) modified = true;
        return modified;
    }

    public boolean removeAll(int[] a) {
        boolean modified = false;
        for (int v : a) if (remove(v)) modified = true;
        return modified;
    }

    public boolean forEach(TIntProcedure procedure) {
        for (int i = 0; i < size; i++) if (!procedure.execute(data[i])) return false;
        return true;
    }

    // ---- Externalizable (matches Ludii's real TIntArrayList format) ----

    @Override
    public void writeExternal(java.io.ObjectOutput out) throws java.io.IOException {
        out.writeByte(0); // version
        out.writeInt(size);
        out.writeInt(no_entry_value);
        out.writeInt(data.length);
        for (int i = 0; i < data.length; i++) out.writeInt(data[i]);
    }

    @Override
    public void readExternal(java.io.ObjectInput in) throws java.io.IOException, ClassNotFoundException {
        in.readByte(); // version
        size = in.readInt();
        no_entry_value = in.readInt();
        int capacity = in.readInt();
        data = new int[Math.max(1, capacity)];
        for (int i = 0; i < capacity; i++) data[i] = in.readInt();
    }

    static class TIntArrayIterator implements TIntIterator {
        private final TIntArrayList list;
        private int _index;
        private int _lastReturned = -1;

        TIntArrayIterator(TIntArrayList list) {
            this.list = list;
        }

        public boolean hasNext() {
            return _index < list.size;
        }

        public int next() {
            if (!hasNext()) throw new IndexOutOfBoundsException();
            _lastReturned = _index;
            return list.data[_index++];
        }

        public void remove() {
            if (_lastReturned < 0) throw new IllegalStateException();
            int idx = _lastReturned;
            _index--;
            _lastReturned = -1;
            int moved = list.size - idx - 1;
            if (moved > 0) System.arraycopy(list.data, idx + 1, list.data, idx, moved);
            list.size--;
        }
    }
}

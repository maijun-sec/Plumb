package chain;

import java.util.ArrayList;
import java.util.List;

/**
 * LAYER 2 / FILE 4 — collection carriers.
 *
 * <p>Three separate container shapes, because each one exercises a different propagation mechanism:
 *
 * <ul>
 *   <li>{@link #addToList} / {@link #firstFromList} — list element store + load.
 *   <li>{@link #addToMap} / {@link #lookupMap} — map value, reached through a key lookup rather than
 *       a positional load.
 *   <li>{@link #buildArray} — array store + array load.
 * </ul>
 *
 * <p>Indexing through a variable ({@code list.get(i)}) rather than a constant is deliberate: a
 * checker that only understands constant array indices will miss it.
 */
public class CollectionCarrier {

  private final List<String> list = new ArrayList<>();
  private final java.util.Map<String, String> map = new java.util.HashMap<>();

  /** Element store into a field-owned list. */
  public void addToList(String tainted) {
    list.add(tainted);
  }

  /** Positional element load through a caller-controlled index. */
  public String firstFromList(int index) {
    if (index < 0 || index >= list.size()) {
      return null;
    }
    return list.get(index);
  }

  /** Map value store. */
  public void addToMap(String key, String tainted) {
    map.put(key, tainted);
  }

  /** Map value load — taint has to survive the key indirection. */
  public String lookupMap(String key) {
    return map.get(key);
  }

  /** Array construction from a tainted element. */
  public String[] buildArray(String tainted) {
    String[] out = new String[2];
    out[0] = tainted;
    return out;
  }

  /** Array load through a variable index. */
  public String readArray(String[] arr, int index) {
    if (index < 0 || index >= arr.length) {
      return null;
    }
    return arr[index];
  }

  /**
   * Taint stored and read within the SAME expression chain, via a local that shadows the field. This
   * is the classic shape that confuses field-vs-local taint bookkeeping.
   */
  public String roundTripThroughLocal(String tainted) {
    String local = tainted;
    this.list.add(local);
    String out = this.list.get(0);
    return out;
  }
}
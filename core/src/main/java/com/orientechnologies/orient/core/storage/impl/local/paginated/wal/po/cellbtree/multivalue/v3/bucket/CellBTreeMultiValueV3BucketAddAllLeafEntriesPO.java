package com.orientechnologies.orient.core.storage.impl.local.paginated.wal.po.cellbtree.multivalue.v3.bucket;

import com.orientechnologies.common.serialization.types.*;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.serialization.serializer.binary.OBinarySerializerFactory;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.WALRecordTypes;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.po.PageOperationRecord;
import com.orientechnologies.orient.core.storage.index.sbtree.multivalue.v3.CellBTreeMultiValueV3Bucket;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class CellBTreeMultiValueV3BucketAddAllLeafEntriesPO extends PageOperationRecord {
  private int                                         prevSize;
  private List<CellBTreeMultiValueV3Bucket.LeafEntry> leafEntries;
  private OBinarySerializer                           keySerializer;

  public CellBTreeMultiValueV3BucketAddAllLeafEntriesPO() {
  }

  public CellBTreeMultiValueV3BucketAddAllLeafEntriesPO(int prevSize, List<CellBTreeMultiValueV3Bucket.LeafEntry> leafEntries,
      OBinarySerializer keySerializer) {
    this.prevSize = prevSize;
    this.leafEntries = leafEntries;
    this.keySerializer = keySerializer;
  }

  public int getPrevSize() {
    return prevSize;
  }

  public List<CellBTreeMultiValueV3Bucket.LeafEntry> getLeafEntries() {
    return leafEntries;
  }

  public OBinarySerializer getKeySerializer() {
    return keySerializer;
  }

  @Override
  public void redo(OCacheEntry cacheEntry) {
    final CellBTreeMultiValueV3Bucket bucket = new CellBTreeMultiValueV3Bucket(cacheEntry);
    //noinspection unchecked
    bucket.addAll(leafEntries, keySerializer);
  }

  @Override
  public void undo(OCacheEntry cacheEntry) {
    final CellBTreeMultiValueV3Bucket bucket = new CellBTreeMultiValueV3Bucket(cacheEntry);
    //noinspection unchecked
    bucket.shrink(prevSize, keySerializer);
  }

  @Override
  public int getId() {
    return WALRecordTypes.CELL_BTREE_BUCKET_MULTI_VALUE_V3_ADD_ALL_LEAF_ENTRIES_PO;
  }

  @Override
  public int serializedSize() {
    int size = OIntegerSerializer.INT_SIZE;

    for (final CellBTreeMultiValueV3Bucket.LeafEntry leafEntry : leafEntries) {
      size += OLongSerializer.LONG_SIZE + 3 * OIntegerSerializer.INT_SIZE;
      size += leafEntry.key.length;
      size += leafEntry.values.size() * (OLongSerializer.LONG_SIZE + OShortSerializer.SHORT_SIZE);
    }

    size += OByteSerializer.BYTE_SIZE + OIntegerSerializer.INT_SIZE;

    return super.serializedSize() + size;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);

    buffer.putInt(leafEntries.size());
    for (final CellBTreeMultiValueV3Bucket.LeafEntry leafEntry : leafEntries) {
      buffer.putInt(leafEntry.entriesCount);
      buffer.putLong(leafEntry.mId);

      buffer.putInt(leafEntry.values.size());
      for (final ORID rid : leafEntry.values) {
        buffer.putShort((short) rid.getClusterId());
        buffer.putLong(rid.getClusterPosition());
      }

      buffer.putInt(leafEntry.key.length);
      buffer.put(leafEntry.key);
    }

    buffer.put(keySerializer.getId());
    buffer.putInt(prevSize);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);

    final int entriesSize = buffer.getInt();
    leafEntries = new ArrayList<>(entriesSize);
    for (int i = 0; i < entriesSize; i++) {
      final int entriesCount = buffer.getInt();
      final long mId = buffer.getLong();

      final int valuesSize = buffer.getInt();
      final List<ORID> values = new ArrayList<>(valuesSize);

      for (int n = 0; n < valuesSize; n++) {
        final int clusterId = buffer.getShort();
        final long clusterPosition = buffer.getLong();

        values.add(new ORecordId(clusterId, clusterPosition));
      }

      final int keyLen = buffer.getInt();
      final byte[] key = new byte[keyLen];
      buffer.get(key);

      leafEntries.add(new CellBTreeMultiValueV3Bucket.LeafEntry(key, mId, values, entriesCount));
    }

    keySerializer = OBinarySerializerFactory.getInstance().getObjectSerializer(buffer.get());
    prevSize = buffer.getInt();
  }

}

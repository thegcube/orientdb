/*
 *
 *  *  Copyright 2010-2016 OrientDB LTD (http://orientdb.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://orientdb.com
 *
 */
package com.orientechnologies.orient.core.storage.index.engine;

import com.orientechnologies.common.serialization.types.OBinarySerializer;
import com.orientechnologies.common.util.OCommonConst;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.encryption.OEncryption;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.index.*;
import com.orientechnologies.orient.core.index.engine.OIndexEngine;
import com.orientechnologies.orient.core.iterator.OEmptyIterator;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.storage.index.hashindex.local.OHashFunction;
import com.orientechnologies.orient.core.storage.index.hashindex.local.OHashTable;
import com.orientechnologies.orient.core.storage.index.hashindex.local.OMurmurHash3HashFunction;
import com.orientechnologies.orient.core.storage.index.hashindex.local.OSHA256HashFunction;
import com.orientechnologies.orient.core.storage.index.hashindex.local.v2.LocalHashTableV2;
import com.orientechnologies.orient.core.storage.index.hashindex.local.v3.OLocalHashTableV3;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Andrey Lomakin (a.lomakin-at-orientdb.com)
 * @since 15.07.13
 */
public final class OHashTableIndexEngine implements OIndexEngine {
  public static final int VERSION = 3;

  public static final String METADATA_FILE_EXTENSION    = ".him";
  public static final String TREE_FILE_EXTENSION        = ".hit";
  public static final String BUCKET_FILE_EXTENSION      = ".hib";
  public static final String NULL_BUCKET_FILE_EXTENSION = ".hnb";

  private final OHashTable<Object, Object> hashTable;
  private final AtomicLong                 bonsayFileId = new AtomicLong(0);

  private final int version;

  private final String name;

  private final int id;

  public OHashTableIndexEngine(String name, int id, OAbstractPaginatedStorage storage, int version) {
    this.id = id;
    this.version = version;
    if (version < 2) {
      throw new IllegalStateException("Unsupported version of hash index");
    } else if (version == 2) {
      hashTable = new LocalHashTableV2<>(id, name, METADATA_FILE_EXTENSION, TREE_FILE_EXTENSION, BUCKET_FILE_EXTENSION,
          NULL_BUCKET_FILE_EXTENSION, storage);
    } else if (version == 3) {
      hashTable = new OLocalHashTableV3<>(id, name, METADATA_FILE_EXTENSION, TREE_FILE_EXTENSION, BUCKET_FILE_EXTENSION,
          NULL_BUCKET_FILE_EXTENSION, storage);
    } else {
      throw new IllegalStateException("Invalid value of the index version , version = " + version);
    }

    this.name = name;
  }

  @Override
  public int getId() {
    return id;
  }

  @Override
  public void init(String indexName, String indexType, OIndexDefinition indexDefinition, boolean isAutomatic, ODocument metadata) {
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public void create(OBinarySerializer valueSerializer, boolean isAutomatic, OType[] keyTypes, boolean nullPointerSupport,
      OBinarySerializer keySerializer, int keySize, Map<String, String> engineProperties, OEncryption encryption)
      throws IOException {
    final OHashFunction<Object> hashFunction;

    if (encryption != null) {
      //noinspection unchecked
      hashFunction = new OSHA256HashFunction<>(keySerializer);
    } else {
      //noinspection unchecked
      hashFunction = new OMurmurHash3HashFunction<>(keySerializer);
    }

    //noinspection unchecked
    hashTable.create(keySerializer, valueSerializer, keyTypes, encryption, hashFunction, nullPointerSupport);
  }

  @Override
  public void flush() {
  }

  @Override
  public String getIndexNameByKey(final Object key) {
    return name;
  }

  @Override
  public void delete() throws IOException {
    doClearTable();

    hashTable.delete();
  }

  private void doClearTable() throws IOException {
    final OHashTable.Entry<Object, Object> firstEntry = hashTable.firstEntry();

    if (firstEntry != null) {
      OHashTable.Entry<Object, Object>[] entries = hashTable.ceilingEntries(firstEntry.key);
      while (entries.length > 0) {
        for (final OHashTable.Entry<Object, Object> entry : entries) {
          hashTable.remove(entry.key);
        }

        entries = hashTable.higherEntries(entries[entries.length - 1].key);
      }
    }

    if (hashTable.isNullKeyIsSupported()) {
      hashTable.remove(null);
    }
  }

  @Override
  public void load(String indexName, OBinarySerializer valueSerializer, boolean isAutomatic, OBinarySerializer keySerializer,
      OType[] keyTypes, boolean nullPointerSupport, int keySize, Map<String, String> engineProperties, OEncryption encryption) {

    final OHashFunction<Object> hashFunction;

    if (encryption != null) {
      //noinspection unchecked
      hashFunction = new OSHA256HashFunction<>(keySerializer);
    } else {
      //noinspection unchecked
      hashFunction = new OMurmurHash3HashFunction<>(keySerializer);
    }
    //noinspection unchecked
    hashTable.load(indexName, keyTypes, nullPointerSupport, encryption, hashFunction, keySerializer, valueSerializer);
  }

  @Override
  public boolean contains(Object key) {
    return hashTable.get(key) != null;
  }

  @Override
  public boolean remove(Object key) throws IOException {
    return hashTable.remove(key) != null;
  }

  @Override
  public void clear() throws IOException {
    doClearTable();
  }

  @Override
  public void close() {
    hashTable.close();
  }

  @Override
  public Object get(Object key) {
    return hashTable.get(key);
  }

  @Override
  public void put(Object key, Object value) throws IOException {
    hashTable.put(key, value);
  }

  @Override
  public void update(Object key, OIndexKeyUpdater<Object> updater) throws IOException {
    Object value = get(key);
    OIndexUpdateAction<Object> updated = updater.update(value, bonsayFileId);
    if (updated.isChange()) {
      put(key, updated.getValue());
    } else if (updated.isRemove()) {
      remove(key);
    } else if (updated.isNothing()) {
      //Do nothing
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public boolean validatedPut(Object key, ORID value, Validator<Object, ORID> validator) throws IOException {
    return hashTable.validatedPut(key, value, (Validator) validator);
  }

  @Override
  public long size(ValuesTransformer transformer) {
    if (transformer == null) {
      return hashTable.size();
    } else {
      long counter = 0;

      if (hashTable.isNullKeyIsSupported()) {
        final Object nullValue = hashTable.get(null);
        if (nullValue != null) {
          counter += transformer.transformFromValue(nullValue).size();
        }
      }

      OHashTable.Entry<Object, Object> firstEntry = hashTable.firstEntry();
      if (firstEntry == null) {
        return counter;
      }

      OHashTable.Entry<Object, Object>[] entries = hashTable.ceilingEntries(firstEntry.key);

      while (entries.length > 0) {
        for (OHashTable.Entry<Object, Object> entry : entries) {
          counter += transformer.transformFromValue(entry.value).size();
        }

        entries = hashTable.higherEntries(entries[entries.length - 1].key);
      }

      return counter;
    }
  }

  @Override
  public int getVersion() {
    return version;
  }

  @Override
  public boolean hasRangeQuerySupport() {
    return false;
  }

  @Override
  public OIndexCursor iterateEntriesBetween(Object rangeFrom, boolean fromInclusive, Object rangeTo, boolean toInclusive,
      boolean ascSortOrder, ValuesTransformer transformer) {
    throw new UnsupportedOperationException("iterateEntriesBetween");
  }

  @Override
  public OIndexCursor iterateEntriesMajor(Object fromKey, boolean isInclusive, boolean ascSortOrder,
      ValuesTransformer transformer) {
    throw new UnsupportedOperationException("iterateEntriesMajor");
  }

  @Override
  public OIndexCursor iterateEntriesMinor(Object toKey, boolean isInclusive, boolean ascSortOrder, ValuesTransformer transformer) {
    throw new UnsupportedOperationException("iterateEntriesMinor");
  }

  @Override
  public Object getFirstKey() {
    throw new UnsupportedOperationException("firstKey");
  }

  @Override
  public Object getLastKey() {
    throw new UnsupportedOperationException("lastKey");
  }

  @Override
  public OIndexCursor cursor(final ValuesTransformer valuesTransformer) {
    return new OIndexAbstractCursor() {
      private int nextEntriesIndex;
      private OHashTable.Entry<Object, Object>[] entries;

      private Iterator<ORID> currentIterator = new OEmptyIterator<>();
      private Object currentKey;

      {
        OHashTable.Entry<Object, Object> firstEntry = hashTable.firstEntry();
        if (firstEntry == null) {
          //noinspection unchecked
          entries = OCommonConst.EMPTY_BUCKET_ENTRY_ARRAY;
        } else {
          entries = hashTable.ceilingEntries(firstEntry.key);
        }

        if (entries.length == 0) {
          currentIterator = null;
        }
      }

      @Override
      public Map.Entry<Object, OIdentifiable> nextEntry() {
        if (currentIterator == null) {
          return null;
        }

        if (currentIterator.hasNext()) {
          return nextCursorValue();
        }

        while (currentIterator != null && !currentIterator.hasNext()) {
          if (entries.length == 0) {
            currentIterator = null;
            return null;
          }

          final OHashTable.Entry<Object, Object> bucketEntry = entries[nextEntriesIndex];

          currentKey = bucketEntry.key;

          Object value = bucketEntry.value;
          if (valuesTransformer != null) {
            currentIterator = valuesTransformer.transformFromValue(value).iterator();
          } else {
            currentIterator = Collections.singletonList((ORID) value).iterator();
          }

          nextEntriesIndex++;

          if (nextEntriesIndex >= entries.length) {
            entries = hashTable.higherEntries(entries[entries.length - 1].key);

            nextEntriesIndex = 0;
          }
        }

        if (currentIterator != null && !currentIterator.hasNext()) {
          return nextCursorValue();
        }

        currentIterator = null;
        return null;
      }

      private Map.Entry<Object, OIdentifiable> nextCursorValue() {
        final OIdentifiable identifiable = currentIterator.next();

        return new Map.Entry<Object, OIdentifiable>() {
          @Override
          public Object getKey() {
            return currentKey;
          }

          @Override
          public OIdentifiable getValue() {
            return identifiable;
          }

          @Override
          public OIdentifiable setValue(OIdentifiable value) {
            throw new UnsupportedOperationException();
          }
        };
      }
    };
  }

  @Override
  public OIndexCursor descCursor(final ValuesTransformer valuesTransformer) {
    return new OIndexAbstractCursor() {
      private int nextEntriesIndex;
      private OHashTable.Entry<Object, Object>[] entries;

      private Iterator<ORID> currentIterator = new OEmptyIterator<>();
      private Object currentKey;

      {
        OHashTable.Entry<Object, Object> lastEntry = hashTable.lastEntry();
        if (lastEntry == null) {
          //noinspection unchecked
          entries = OCommonConst.EMPTY_BUCKET_ENTRY_ARRAY;
        } else {
          entries = hashTable.floorEntries(lastEntry.key);
        }

        if (entries.length == 0) {
          currentIterator = null;
        }
      }

      @Override
      public Map.Entry<Object, OIdentifiable> nextEntry() {
        if (currentIterator == null) {
          return null;
        }

        if (currentIterator.hasNext()) {
          return nextCursorValue();
        }

        while (currentIterator != null && !currentIterator.hasNext()) {
          if (entries.length == 0) {
            currentIterator = null;
            return null;
          }

          final OHashTable.Entry<Object, Object> bucketEntry = entries[nextEntriesIndex];

          currentKey = bucketEntry.key;

          Object value = bucketEntry.value;
          if (valuesTransformer != null) {
            currentIterator = valuesTransformer.transformFromValue(value).iterator();
          } else {
            currentIterator = Collections.singletonList((ORID) value).iterator();
          }

          nextEntriesIndex--;

          if (nextEntriesIndex < 0) {
            entries = hashTable.lowerEntries(entries[0].key);

            nextEntriesIndex = entries.length - 1;
          }
        }

        if (currentIterator != null && !currentIterator.hasNext()) {
          return nextCursorValue();
        }

        currentIterator = null;
        return null;
      }

      private Map.Entry<Object, OIdentifiable> nextCursorValue() {
        final OIdentifiable identifiable = currentIterator.next();

        return new Map.Entry<Object, OIdentifiable>() {
          @Override
          public Object getKey() {
            return currentKey;
          }

          @Override
          public OIdentifiable getValue() {
            return identifiable;
          }

          @Override
          public OIdentifiable setValue(OIdentifiable value) {
            throw new UnsupportedOperationException();
          }
        };
      }
    };
  }

  @Override
  public OIndexKeyCursor keyCursor() {
    return new OIndexKeyCursor() {
      private int nextEntriesIndex;
      private OHashTable.Entry<Object, Object>[] entries;

      {
        OHashTable.Entry<Object, Object> firstEntry = hashTable.firstEntry();
        if (firstEntry == null) {
          //noinspection unchecked
          entries = OCommonConst.EMPTY_BUCKET_ENTRY_ARRAY;
        } else {
          entries = hashTable.ceilingEntries(firstEntry.key);
        }
      }

      @Override
      public Object next(int prefetchSize) {
        if (entries.length == 0) {
          return null;
        }

        final OHashTable.Entry<Object, Object> bucketEntry = entries[nextEntriesIndex];
        nextEntriesIndex++;
        if (nextEntriesIndex >= entries.length) {
          entries = hashTable.higherEntries(entries[entries.length - 1].key);

          nextEntriesIndex = 0;
        }

        return bucketEntry.key;
      }
    };
  }

  @Override
  public boolean acquireAtomicExclusiveLock(Object key) {
    hashTable.acquireAtomicExclusiveLock();
    return true;
  }

}

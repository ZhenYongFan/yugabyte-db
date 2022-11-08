// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

#pragma once

#include "yb/docdb/key_bytes.h"
#include "yb/rocksdb/rocksdb_fwd.h"

namespace yb {
namespace docdb {

// Optional inclusive lower bound and exclusive upper bound for keys served by DocDB.
// Could be used to split tablet without doing actual splitting of RocksDB files.
// DocDBCompactionFilter also respects these bounds, so it will filter out non-relevant keys
// during compaction.
// Both bounds should be encoded DocKey or its part to avoid splitting DocDB row.
struct KeyBounds {
  KeyBytes lower;
  KeyBytes upper;

  static const KeyBounds kNoBounds;

  KeyBounds() = default;
  KeyBounds(const Slice& _lower, const Slice& _upper) : lower(_lower), upper(_upper) {}

  bool IsWithinBounds(const Slice& key) const {
    return (lower.empty() || key.compare(lower) >= 0) &&
           (upper.empty() || key.compare(upper) < 0);
  }

  bool IsInitialized() const {
    return !lower.empty() || !upper.empty();
  }

  std::string ToString() const;
};

// Combined DB to store regular records and intents.
// TODO: move this to a more appropriate header file.
struct DocDB {
  rocksdb::DB* regular = nullptr;
  rocksdb::DB* intents = nullptr;
  const KeyBounds* key_bounds = nullptr;

  static DocDB FromRegularUnbounded(rocksdb::DB* regular) {
    return {regular, nullptr /* intents */, &KeyBounds::kNoBounds};
  }

  DocDB WithoutIntents() {
    return {regular, nullptr /* intents */, key_bounds};
  }
};

// Checks whether key belongs to specified key_bounds, always true if key_bounds is nullptr.
bool IsWithinBounds(const KeyBounds* key_bounds, const Slice& key);

}  // namespace docdb
}  // namespace yb

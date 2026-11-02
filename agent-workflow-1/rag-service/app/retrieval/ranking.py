from collections import Counter


def tie_key(row):
    # Fresh ingestion assigns random UUIDs. Prefer stable content/source keys for
    # equivalent scores, so independent corpus rebuilds don't randomize quality.
    return (row.get("content_hash", ""), row.get("title", ""), row.get("ordinal", 0), str(row["chunk_id"]))


def fuse(channels, rrf_k=60):
    """Ranks, not uncalibrated dense/lexical/reranker scores, are combined."""
    merged = {}
    for channel, rows in channels.items():
        seen = set()
        for rank, row in enumerate(rows, 1):
            key = str(row["chunk_id"])
            if key in seen:
                continue
            seen.add(key)
            hit = merged.setdefault(key, dict(row, channel_ranks={}, channel_scores={}, fusion_score=0.0))
            hit["channel_ranks"][channel] = rank
            hit["channel_scores"][channel] = row.get("channel_score")
            hit["fusion_score"] += 1.0 / (rrf_k + rank)
            if row.get("distance") is not None:
                hit["distance"] = row["distance"]
    return sorted(merged.values(), key=lambda row: (-row["fusion_score"], tie_key(row)))


def assemble(rows, top_k, token_budget, max_per_document):
    """Whole original chunks only: citations never silently refer to trimmed/reworded text."""
    seen_hashes, counts, selected = set(), Counter(), []
    used = 0
    for row in rows:
        if row["content_hash"] in seen_hashes or counts[row["document_id"]] >= max_per_document:
            continue
        # Published token_count is from the BGE tokenizer; include a small citation wrapper reserve.
        cost = row["token_count"] + 24
        if used + cost > token_budget:
            continue
        selected.append(row)
        used += cost
        seen_hashes.add(row["content_hash"])
        counts[row["document_id"]] += 1
        if len(selected) == top_k:
            break
    return selected, used

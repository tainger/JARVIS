package com.example.jarvis.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.jarvis.mapper.KnowledgeMapper;
import com.example.jarvis.mapper.KnowledgeSearchLogMapper;
import com.example.jarvis.model.KnowledgeDocument;

@Service
public class KnowledgeHealthService {

	private static final Logger log = LoggerFactory.getLogger(KnowledgeHealthService.class);

	private final KnowledgeMapper knowledgeMapper;
	private final KnowledgeSearchLogMapper searchLogMapper;

	public KnowledgeHealthService(KnowledgeMapper knowledgeMapper,
			KnowledgeSearchLogMapper searchLogMapper) {
		this.knowledgeMapper = knowledgeMapper;
		this.searchLogMapper = searchLogMapper;
	}

	public Map<String, Object> getZombieDocs() {
		List<KnowledgeDocument> allDocs = knowledgeMapper.findAllDocuments();
		Set<Long> referencedIds = getReferencedDocIds();

		List<Map<String, Object>> zombies = new ArrayList<>();
		for (KnowledgeDocument doc : allDocs) {
			if (!referencedIds.contains(doc.getId())) {
				zombies.add(docToMap(doc));
			}
		}

		Map<String, Object> result = new HashMap<>();
		result.put("items", zombies);
		result.put("totalDocuments", allDocs.size());
		result.put("zombieCount", zombies.size());
		return result;
	}

	public List<Map<String, Object>> getBlindSpots() {
		List<Map<String, Object>> raw = searchLogMapper.selectBlindSpots();
		List<Map<String, Object>> result = new ArrayList<>();
		for (Map<String, Object> m : raw) {
			Map<String, Object> item = new HashMap<>();
			item.put("query", m.get("query"));
			item.put("traceCount", toLong(m.get("trace_count")));
			item.put("lastSeen", m.get("last_seen"));
			result.add(item);
		}
		return result;
	}

	public List<Map<String, Object>> getDocHeat() {
		List<KnowledgeDocument> allDocs = knowledgeMapper.findAllDocuments();
		List<Map<String, Object>> refRows = searchLogMapper.selectAllReferencedDocIds();

		Map<Long, Integer> refCount = new HashMap<>();
		for (Map<String, Object> row : refRows) {
			String docIds = (String) row.get("doc_ids");
			if (docIds == null || docIds.isBlank()) continue;
			Set<Long> seen = new HashSet<>();
			for (String id : docIds.split(",")) {
				try {
					long docId = Long.parseLong(id.trim());
					if (seen.add(docId)) {
						refCount.merge(docId, 1, Integer::sum);
					}
				} catch (NumberFormatException e) { /* skip */ }
			}
		}

		List<Map<String, Object>> result = new ArrayList<>();
		for (KnowledgeDocument doc : allDocs) {
			int count = refCount.getOrDefault(doc.getId(), 0);
			Map<String, Object> item = new HashMap<>();
			item.put("id", doc.getId());
			item.put("title", doc.getTitle());
			item.put("referenceCount", count);
			item.put("chunkCount", doc.getChunkCount());
			item.put("hasDislike", false);
			item.put("dislikeCount", 0);
			result.add(item);
		}
		result.sort((a, b) -> Integer.compare(
				(int) b.get("referenceCount"), (int) a.get("referenceCount")));
		return result;
	}

	private Set<Long> getReferencedDocIds() {
		List<Map<String, Object>> rows = searchLogMapper.selectAllReferencedDocIds();
		Set<Long> ids = new HashSet<>();
		for (Map<String, Object> row : rows) {
			String docIds = (String) row.get("doc_ids");
			if (docIds == null || docIds.isBlank()) continue;
			for (String id : docIds.split(",")) {
				try { ids.add(Long.parseLong(id.trim())); }
				catch (NumberFormatException e) { /* skip */ }
			}
		}
		return ids;
	}

	private Map<String, Object> docToMap(KnowledgeDocument doc) {
		Map<String, Object> m = new HashMap<>();
		m.put("id", doc.getId());
		m.put("title", doc.getTitle());
		m.put("chunk_count", doc.getChunkCount());
		m.put("created_at", doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : null);
		return m;
	}

	private long toLong(Object o) {
		if (o == null) return 0;
		if (o instanceof Number n) return n.longValue();
		try { return Long.parseLong(o.toString()); } catch (Exception e) { return 0; }
	}
}

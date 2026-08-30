package com.example.jarvis.mapper;

import java.util.List;

import com.example.jarvis.model.EvalCandidate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EvalCandidateMapper {

	EvalCandidate findById(@Param("id") Long id);

	/** pending 状态下按规范化问题查重（同题多提直接 409）。 */
	EvalCandidate findPendingByNorm(@Param("questionNorm") String questionNorm);

	int insert(EvalCandidate candidate);

	List<EvalCandidate> findByStatus(@Param("status") String status,
			@Param("offset") int offset, @Param("size") int size);

	long countByStatus(@Param("status") String status);

	/** CAS 式 triage：只对 pending 生效，影响行数 0 = 已被处理（409）。 */
	int markPromoted(@Param("id") Long id, @Param("expectedDoc") String expectedDoc);

	int markDiscarded(@Param("id") Long id);
}

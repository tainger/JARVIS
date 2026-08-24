package com.example.jarvis.mapper;

import java.util.List;

import com.example.jarvis.model.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskMapper {

	List<Task> findAll();

	Task findById(@Param("id") Long id);

	int insert(Task task);

	int update(Task task);

	int deleteById(@Param("id") Long id);

	int countById(@Param("id") Long id);

}

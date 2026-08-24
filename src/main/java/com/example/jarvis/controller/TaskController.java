package com.example.jarvis.controller;

import java.util.List;

import com.example.jarvis.mapper.TaskMapper;
import com.example.jarvis.model.Task;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

	private final TaskMapper taskMapper;

	public TaskController(TaskMapper taskMapper) {
		this.taskMapper = taskMapper;
	}

	@GetMapping
	public List<Task> getAllTasks() {
		return taskMapper.findAll();
	}

	@GetMapping("/{id}")
	public ResponseEntity<Task> getTaskById(@PathVariable Long id) {
		Task task = taskMapper.findById(id);
		return task != null ? ResponseEntity.ok(task) : ResponseEntity.notFound().build();
	}

	@PostMapping
	public ResponseEntity<Task> createTask(@RequestBody Task task) {
		taskMapper.insert(task);
		return ResponseEntity.status(HttpStatus.CREATED).body(task);
	}

	@PutMapping("/{id}")
	public ResponseEntity<Task> updateTask(@PathVariable Long id, @RequestBody Task task) {
		if (taskMapper.countById(id) == 0) {
			return ResponseEntity.notFound().build();
		}
		task.setId(id);
		taskMapper.update(task);
		return ResponseEntity.ok(task);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> deleteTask(@PathVariable Long id) {
		if (taskMapper.countById(id) == 0) {
			return ResponseEntity.notFound().build();
		}
		taskMapper.deleteById(id);
		return ResponseEntity.noContent().build();
	}

}

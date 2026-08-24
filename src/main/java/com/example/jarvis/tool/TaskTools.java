package com.example.jarvis.tool;

import java.util.List;

import com.example.jarvis.mapper.TaskMapper;
import com.example.jarvis.model.Task;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class TaskTools {

	private final TaskMapper taskMapper;

	public TaskTools(TaskMapper taskMapper) {
		this.taskMapper = taskMapper;
	}

	@Tool(description = "List all tasks stored in the task database.")
	public String listTasks() {
		List<Task> tasks = taskMapper.findAll();
		if (tasks.isEmpty()) {
			return "No tasks found.";
		}
		return tasks.toString();
	}

	@Tool(description = "Get a single task by its numeric id.")
	public String getTask(
			@ToolParam(name = "id", description = "The numeric id of the task") Long id) {
		Task task = taskMapper.findById(id);
		return task != null ? task.toString() : "Task " + id + " not found.";
	}

	@Tool(description = "Create a new task with a title and optional description.")
	public String createTask(
			@ToolParam(name = "title", description = "Task title") String title,
			@ToolParam(name = "description", description = "Task description, may be empty") String description) {
		Task task = new Task(title, description, false);
		taskMapper.insert(task);
		return "Task created: " + task;
	}

}

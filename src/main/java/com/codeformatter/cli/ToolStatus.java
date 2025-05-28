package com.codeformatter.cli;

public class ToolStatus {
  final boolean available;
  final String message;
  final String suggestion;

  ToolStatus(boolean available, String message, String suggestion) {
    this.available = available;
    this.message = message;
    this.suggestion = suggestion;
  }
}

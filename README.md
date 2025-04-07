# Advanced Source Code Formatter

An advanced code formatter and analyzer for Spring Boot and React JS codebases that goes beyond simple formatting to improve code structure and quality.

## Features

- **Spring Boot**:
  - Method size and complexity analysis
  - Design pattern detection and enforcement
  - Spring component organization
  - Import and member ordering

- **React JS**:
  - Component structure analysis
  - Hook usage optimization
  - State management improvement
  - JSX formatting and organization

## Quick Start

### Installation

```bash
# Clone the repository
git clone https://github.com/yourusername/advanced-formatter.git
cd advanced-formatter

# Build the project
./gradlew build

# Create executable
./gradlew installDist
```

### Basic Usage

```bash
# Initialize configuration
bin/codeformatter init

# Format files
bin/codeformatter format path/to/your/code

# Check files without modifying (CI-friendly)
bin/codeformatter check path/to/your/code
```

## Configuration

Edit the `.codeformatter.yml` file in your project root to customize formatter behavior:

```yaml
general:
  indentSize: 4
  lineLength: 100
  
plugins:
  spring:
    maxMethodLines: 50
    maxMethodComplexity: 15
    
  react:
    maxComponentLines: 150
    enforceHookDependencies: true
```

## Integration

- IDE plugins available for IntelliJ and VS Code
- Git hooks for pre-commit formatting
- CI/CD pipeline integration
- Build tool plugins (Maven, Gradle, npm)

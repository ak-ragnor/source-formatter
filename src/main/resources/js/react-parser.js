/**
 * This script integrates Babel Parser and Prettier to provide React code parsing and formatting.
 * It requires babel-standalone.min.js and prettier.min.js to be loaded first.
 */

const babelParser = Babel.babylon;
const babelGenerator = Babel.generate;
const babelTraverse = Babel.traverse;
const babelTypes = Babel.types;

/**
 * Parse React code and return an AST
 */
function parseReactCode(code, isTypeScript) {
  try {
    const plugins = [
      'jsx',
      'classProperties',
      'objectRestSpread',
      'dynamicImport',
      'decorators-legacy',
      'asyncGenerators',
      'exportDefaultFrom',
      'exportNamespaceFrom',
      'optionalChaining',
      'nullishCoalescingOperator',
    ];

    if (isTypeScript) {
      plugins.push('typescript');
    }

    const ast = babelParser.parse(code, {
      sourceType: 'module',
      plugins: plugins,
    });

    ast.findNodes = function (nodeType) {
      const nodes = [];
      babelTraverse(ast, {
        [nodeType]: function (path) {
          nodes.push(path.node);
        },
      });
      return nodes;
    };

    return {
      ast: ast,
    };
  } catch (error) {
    return {
      error: error.message,
    };
  }
}

/**
 * Generate code from an AST
 */
function generateCodeFromAst(ast) {
  try {
    const result = babelGenerator(ast, {
      retainLines: true,
      comments: true,
      compact: false,
      jsescOption: {
        quotes: 'single',
      },
    });

    const formattedCode = prettier.format(result.code, {
      parser: 'babel',
      singleQuote: true,
      trailingComma: 'es5',
      bracketSpacing: true,
      jsxBracketSameLine: false,
      semi: true,
      tabWidth: 2,
      printWidth: 100,
    });

    return {
      code: formattedCode,
    };
  } catch (error) {
    return {
      error: error.message,
    };
  }
}

/**
 * Apply a transformation to the AST
 */
function applyTransformation(ast, transformName, options) {
  try {
    switch (transformName) {
      case 'organizeImports':
        organizeImports(ast, options);
        break;
      case 'extractComponent':
        extractComponent(ast, options);
        break;
      case 'fixHookDependencies':
        fixHookDependencies(ast, options);
        break;
      case 'improveJsxStyle':
        improveJsxStyle(ast, options);
        break;
      case 'replaceTypeCast':
        return replaceTypeCast(ast, options);
      case 'replaceNonNullAssertion':
        return replaceNonNullAssertion(ast, options);
      case 'fixSwitchStatement':
        return fixSwitchStatement(ast, options);
      default:
        return { success: false };
    }

    return { success: true };
  } catch (error) {
    console.error('Error applying transformation:', error);
    return { success: false };
  }
}

/**
 * Organize imports by group
 */
function organizeImports(ast, options) {
  const importGroups = options.groups || [
    'react',
    'external',
    'internal',
    'css',
  ];
  const importDeclarations = ast.findNodes('ImportDeclaration');

  if (importDeclarations.length === 0) {
    return;
  }

  const groupedImports = {};
  importGroups.forEach(group => {
    groupedImports[group] = [];
  });

  function getImportGroup(importPath) {
    const path = importPath.value;

    if (path === 'react' || path.startsWith('react-')) {
      return 'react';
    } else if (
      path.startsWith('./') ||
      path.startsWith('../') ||
      path.startsWith('/')
    ) {
      return 'internal';
    } else if (
      path.endsWith('.css') ||
      path.endsWith('.scss') ||
      path.endsWith('.less')
    ) {
      return 'css';
    } else {
      return 'external';
    }
  }

  importDeclarations.forEach(node => {
    const group = getImportGroup(node.source);
    groupedImports[group].push(node);
  });

  Object.values(groupedImports).forEach(group => {
    group.sort((a, b) => a.source.value.localeCompare(b.source.value));
  });

  babelTraverse(ast, {
    ImportDeclaration(path) {
      path.remove();
    },
  });

  const program = ast.program;
  let lastImportIndex = 0;

  importGroups.forEach(group => {
    if (groupedImports[group].length > 0) {
      groupedImports[group].forEach(importNode => {
        program.body.splice(lastImportIndex++, 0, importNode);
      });

      if (lastImportIndex > 0) {
        lastImportIndex++;
      }
    }
  });
}

/**
 * Fix issues with React hook dependencies
 */
function fixHookDependencies(ast, options) {
  babelTraverse(ast, {
    CallExpression(path) {
      const node = path.node;

      if (
        !babelTypes.isIdentifier(node.callee) ||
        (node.callee.name !== 'useEffect' &&
          node.callee.name !== 'useCallback' &&
          node.callee.name !== 'useMemo')
      ) {
        return;
      }

      if (
        node.arguments.length < 2 ||
        !babelTypes.isArrayExpression(node.arguments[1])
      ) {
        return;
      }

      const callback = node.arguments[0];
      if (
        !babelTypes.isArrowFunctionExpression(callback) &&
        !babelTypes.isFunctionExpression(callback)
      ) {
        return;
      }

      const usedIdentifiers = new Set();
      babelTraverse(
        callback,
        {
          Identifier(idPath) {
            const idNode = idPath.node;
            const idName = idNode.name;

            if (
              idPath.scope.hasBinding(idName) ||
              [
                'document',
                'window',
                'console',
                'Math',
                'JSON',
                'parseInt',
                'undefined',
              ].includes(idName)
            ) {
              return;
            }

            usedIdentifiers.add(idName);
          },

          FunctionDeclaration() {
            return false;
          },
          FunctionExpression() {
            return false;
          },
          ArrowFunctionExpression() {
            return false;
          },
        },
        path.scope
      );

      const dependencies = Array.from(usedIdentifiers).sort();
      node.arguments[1] = babelTypes.arrayExpression(
        dependencies.map(dep => babelTypes.identifier(dep))
      );
    },
  });
}

/**
 * Extract components from large components
 */
function extractComponent(ast, options) {
  const maxComponentLines = options.maxComponentLines || 150;

  babelTraverse(ast, {
    FunctionDeclaration(path) {
      if (!_isFunctionComponent(path.node)) return;

      // Check if the component is large
      if (!_isLargeComponent(path.node, maxComponentLines)) return;

      // Find JSX elements that can be extracted
      _extractNestedComponents(path);
    },

    ArrowFunctionExpression(path) {
      // Similar check for arrow function components
      if (!_isComponentFunction(path.node)) return;

      if (!_isLargeComponent(path.node, maxComponentLines)) return;

      _extractNestedComponents(path);
    },
  });

  // Helper function to check if a node is a function component
  function _isFunctionComponent(node) {
    if (!node.id) return false;

    // Components should start with uppercase
    const name = node.id.name;
    if (!name || name[0] !== name[0].toUpperCase()) return false;

    // Check if it returns JSX
    return _returnsJSX(node);
  }

  // Helper function to check if an arrow function is a component
  function _isComponentFunction(node) {
    // For arrow functions, we check the parent for the name
    if (node.body.type === 'JSXElement' || node.body.type === 'JSXFragment') {
      return true;
    }

    if (node.body.type === 'BlockStatement') {
      return _containsJSXReturn(node.body);
    }

    return false;
  }

  // Check if a function returns JSX
  function _returnsJSX(node) {
    if (!node.body || node.body.type !== 'BlockStatement') return false;

    return _containsJSXReturn(node.body);
  }

  // Check if a block contains a return statement with JSX
  function _containsJSXReturn(blockStatement) {
    for (const statement of blockStatement.body) {
      if (
        statement.type === 'ReturnStatement' &&
        statement.argument &&
        (statement.argument.type === 'JSXElement' ||
          statement.argument.type === 'JSXFragment')
      ) {
        return true;
      }
    }
    return false;
  }

  // Check if a component is large based on LOC
  function _isLargeComponent(node, maxLines) {
    if (!node.loc) return false;

    const lineCount = node.loc.end.line - node.loc.start.line;
    return lineCount > maxLines;
  }

  // Extract nested components from a large component
  function _extractNestedComponents(path) {
    const componentName = path.node.id
      ? path.node.id.name
      : 'AnonymousComponent';
    const jsxElements = [];

    // Find nested JSX elements that can be extracted
    path.traverse({
      JSXElement(jsxPath) {
        // Don't extract the root element
        if (
          jsxPath.parent.type === 'ReturnStatement' &&
          jsxPath.parentPath.parent === path.node.body
        ) {
          return;
        }

        // Don't extract small elements (less than 5 lines)
        if (
          !jsxPath.node.loc ||
          jsxPath.node.loc.end.line - jsxPath.node.loc.start.line < 5
        ) {
          return;
        }

        // Check if it has multiple children or props
        const hasMultipleChildren =
          jsxPath.node.children && jsxPath.node.children.length > 2;
        const hasMultipleProps =
          jsxPath.node.openingElement.attributes &&
          jsxPath.node.openingElement.attributes.length > 2;

        if (hasMultipleChildren || hasMultipleProps) {
          jsxElements.push(jsxPath);
        }
      },
    });

    // Sort elements by size (largest first)
    jsxElements.sort((a, b) => {
      const aSize = a.node.loc.end.line - a.node.loc.start.line;
      const bSize = b.node.loc.end.line - b.node.loc.start.line;
      return bSize - aSize;
    });

    // Extract top 2-3 largest elements
    const elementsToExtract = jsxElements.slice(
      0,
      Math.min(3, jsxElements.length)
    );

    for (let i = 0; i < elementsToExtract.length; i++) {
      const jsxPath = elementsToExtract[i];
      const elementType = jsxPath.node.openingElement.name.name;

      // Create a name for the extracted component
      let extractedName;
      if (elementType[0] === elementType[0].toUpperCase()) {
        // Already capitalized
        extractedName = `${elementType}Section`;
      } else {
        // Capitalize the element type
        extractedName =
          elementType[0].toUpperCase() + elementType.slice(1) + 'Section';
      }

      // Create extracted component
      const props = [];
      const usedVariables = new Set();

      // Find variables used in the JSX that are defined outside
      jsxPath.traverse({
        Identifier(idPath) {
          const name = idPath.node.name;

          // Skip if it's a JSX element name or a property name
          if (
            idPath.parent.type === 'JSXIdentifier' ||
            (idPath.parent.type === 'JSXAttribute' && idPath.key === 'name')
          ) {
            return;
          }

          // Skip if it's defined within this JSX element
          if (idPath.scope.bindings[name]) return;

          // Skip common globals
          if (['undefined', 'null', 'true', 'false'].includes(name)) return;

          usedVariables.add(name);
        },
      });

      // Convert used variables to props
      for (const varName of usedVariables) {
        props.push(
          babelTypes.objectProperty(
            babelTypes.identifier(varName),
            babelTypes.identifier(varName),
            false,
            true // shorthand
          )
        );
      }

      // Create a new component function
      const newComponent = babelTypes.functionDeclaration(
        babelTypes.identifier(extractedName),
        [babelTypes.identifier('props')],
        babelTypes.blockStatement([babelTypes.returnStatement(jsxPath.node)])
      );

      // Replace the JSX with a call to the new component
      const propsObj = babelTypes.objectExpression(props);
      const newJSX = babelTypes.jsxElement(
        babelTypes.jsxOpeningElement(
          babelTypes.jsxIdentifier(extractedName),
          props.length > 0
            ? [
                babelTypes.jsxAttribute(
                  babelTypes.jsxIdentifier('props'),
                  babelTypes.jsxExpressionContainer(propsObj)
                ),
              ]
            : [],
          true
        ),
        null,
        [],
        true
      );

      jsxPath.replaceWith(newJSX);

      // Add the new component to the program
      const program = path.findParent(p => p.isProgram());
      program.node.body.push(newComponent);
    }
  }
}

/**
 * Improve JSX style
 */
function improveJsxStyle(ast, options) {
  const lineBreakRule = options.lineBreakRule || 'multiline';

  babelTraverse(ast, {
    JSXAttribute(path) {
      // Handle inline styles
      if (
        path.node.name.name === 'style' &&
        babelTypes.isJSXExpressionContainer(path.node.value)
      ) {
        const styleObj = path.node.value.expression;

        // Only process object expressions (not variables)
        if (babelTypes.isObjectExpression(styleObj)) {
          // If the style has more than 3 properties, suggest using a CSS class
          if (styleObj.properties.length > 3) {
            // Format the inline style object with proper spacing and line breaks
            styleObj.properties.forEach((prop, index) => {
              // Set location info for prettier formatting
              if (prop.loc) {
                prop.loc.start.column = 12; // Indent properties
                prop.loc.end.column =
                  prop.loc.start.column +
                  prop.key.name.length +
                  prop.value.loc.end.column -
                  prop.value.loc.start.column +
                  2;
              }
            });
          }
        }
      }
    },

    JSXElement(path) {
      // Handle elements with many children
      if (path.node.children.length > 3) {
        // Format children with proper line breaks by setting location data
        path.node.children.forEach((child, index) => {
          if (child.loc) {
            // Set indentation for children
            child.loc.start.column = path.node.loc.start.column + 2;
          }
        });
      }

      // Handle elements with many attributes
      const attrs = path.node.openingElement.attributes;
      if (attrs.length > 3 && lineBreakRule === 'multiline') {
        // Format attributes with one per line by setting location data
        attrs.forEach((attr, index) => {
          if (attr.loc) {
            attr.loc.start.line = path.node.loc.start.line + index + 1;
            attr.loc.start.column = path.node.loc.start.column + 2;
          }
        });

        // Set location data for the closing bracket
        if (path.node.openingElement.loc) {
          path.node.openingElement.loc.end.line =
            path.node.openingElement.loc.start.line + attrs.length + 1;
        }

        // Set location data for the closing element
        if (path.node.closingElement && path.node.closingElement.loc) {
          path.node.closingElement.loc.start.column =
            path.node.loc.start.column;
        }
      }
    },

    // Improve JSX fragments
    JSXFragment(path) {
      // If fragment has only one child, replace with the child
      if (path.node.children.length === 1) {
        const child = path.node.children[0];

        // Only replace if the child is an element (not text)
        if (babelTypes.isJSXElement(child)) {
          path.replaceWith(child);
        }
      }

      // If fragment is empty, replace with null
      if (path.node.children.length === 0) {
        path.replaceWith(babelTypes.nullLiteral());
      }
    },
  });

  // Second pass for more complex transformations
  babelTraverse(ast, {
    JSXElement(path) {
      // Improve elements with excessive attributes
      if (path.node.openingElement.attributes.length > 7) {
        // Group related props together
        const propsGroups = {
          event: [],
          data: [],
          style: [],
          aria: [],
          other: [],
        };

        path.node.openingElement.attributes.forEach(attr => {
          if (!babelTypes.isJSXAttribute(attr)) return;

          const name = attr.name.name;

          if (name.startsWith('on')) {
            propsGroups.event.push(attr);
          } else if (name.startsWith('data-')) {
            propsGroups.data.push(attr);
          } else if (name === 'style' || name === 'className') {
            propsGroups.style.push(attr);
          } else if (name.startsWith('aria-')) {
            propsGroups.aria.push(attr);
          } else {
            propsGroups.other.push(attr);
          }
        });

        // Reorder attributes by group
        const newAttributes = [
          ...propsGroups.style,
          ...propsGroups.other,
          ...propsGroups.event,
          ...propsGroups.data,
          ...propsGroups.aria,
        ];

        path.node.openingElement.attributes = newAttributes;
      }
    },
  });

  function replaceTypeCast(ast, options) {
    try {
      const astNodeId = options.astNodeId;
      const newType = options.newType || 'unknown';

      let found = false;

      babelTraverse(ast, {
        TSAsExpression(path) {
          if (path.node.id === astNodeId) {
            found = true;

            // Replace 'any' with 'unknown'
            if (path.node.typeAnnotation.type === 'TSAnyKeyword') {
              path.node.typeAnnotation = babelTypes.tsUnknownKeyword();
            }
          }
        },
      });

      return { success: found };
    } catch (error) {
      return { success: false };
    }
  }

  function replaceNonNullAssertion(ast, options) {
    try {
      const astNodeId = options.astNodeId;

      let found = false;

      babelTraverse(ast, {
        TSNonNullExpression(path) {
          if (path.node.id === astNodeId) {
            found = true;

            // Get the expression without the non-null assertion
            const expr = path.node.expression;

            // Create a logical expression that checks for null/undefined
            const nullCheck = babelTypes.logicalExpression(
              '??',
              expr,
              babelTypes.identifier('undefined')
            );

            // Replace the non-null assertion with the null check
            path.replaceWith(nullCheck);
          }
        },
      });

      return { success: found };
    } catch (error) {
      return { success: false };
    }
  }

  function fixSwitchStatement(ast, options) {
    try {
      const astNodeId = options.astNodeId;
      const ensureDefault = options.ensureDefault !== false;
      const moveDefaultToEnd = options.moveDefaultToEnd !== false;

      let found = false;

      babelTraverse(ast, {
        SwitchStatement(path) {
          if (path.node.id === astNodeId) {
            found = true;

            // Find if there's a default case
            let defaultCase = null;
            let defaultIndex = -1;

            for (let i = 0; i < path.node.cases.length; i++) {
              if (path.node.cases[i].test === null) {
                defaultCase = path.node.cases[i];
                defaultIndex = i;
                break;
              }
            }

            // If no default case and we should add one
            if (defaultCase === null && ensureDefault) {
              // Create an empty default case
              defaultCase = babelTypes.switchCase(null, [
                babelTypes.breakStatement(),
              ]);

              // Add the default case
              path.node.cases.push(defaultCase);
            }
            // If default case exists but it's not the last one
            else if (
              defaultCase !== null &&
              moveDefaultToEnd &&
              defaultIndex !== path.node.cases.length - 1
            ) {
              // Remove the default case from its current position
              path.node.cases.splice(defaultIndex, 1);

              // Add it to the end
              path.node.cases.push(defaultCase);
            }
          }
        },
      });

      return { success: found };
    } catch (error) {
      return { success: false };
    }
  }

  function _isFunctionComponent(node) {
    if (!node.id) return false;

    // Components should start with uppercase
    const name = node.id.name;
    if (!name || name[0] !== name[0].toUpperCase()) return false;

    // Check if it returns JSX
    return _returnsJSX(node);
  }

  function _returnsJSX(node) {
    if (!node.body || node.body.type !== 'BlockStatement') return false;

    for (const statement of node.body.body) {
      if (
        statement.type === 'ReturnStatement' &&
        statement.argument &&
        (statement.argument.type === 'JSXElement' ||
          statement.argument.type === 'JSXFragment')
      ) {
        return true;
      }
    }
    return false;
  }

  function _isLargeComponent(node, maxLines) {
    if (!node.loc) return false;

    const lineCount = node.loc.end.line - node.loc.start.line;
    return lineCount > maxLines;
  }
}

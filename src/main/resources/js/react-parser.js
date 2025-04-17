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
            plugins: plugins
        });


        ast.findNodes = function(nodeType) {
            const nodes = [];
            babelTraverse(ast, {
                [nodeType]: function(path) {
                    nodes.push(path.node);
                }
            });
            return nodes;
        };

        return {
            ast: ast
        };
    } catch (error) {
        return {
            error: error.message
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
                quotes: 'single'
            }
        });


        const formattedCode = prettier.format(result.code, {
            parser: 'babel',
            singleQuote: true,
            trailingComma: 'es5',
            bracketSpacing: true,
            jsxBracketSameLine: false,
            semi: true,
            tabWidth: 2,
            printWidth: 100
        });

        return {
            code: formattedCode
        };
    } catch (error) {
        return {
            error: error.message
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
            default:
                return { success: false };
        }

        return { success: true };
    } catch (error) {
        return { success: false };
    }
}

/**
 * Organize imports by group
 */
function organizeImports(ast, options) {
    const importGroups = options.groups || ['react', 'external', 'internal', 'css'];
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
        } else if (path.startsWith('./') || path.startsWith('../') || path.startsWith('/')) {
            return 'internal';
        } else if (path.endsWith('.css') || path.endsWith('.scss') || path.endsWith('.less')) {
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
        }
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


            if (!babelTypes.isIdentifier(node.callee) ||
                (node.callee.name !== 'useEffect' && node.callee.name !== 'useCallback' && node.callee.name !== 'useMemo')) {
                return;
            }


            if (node.arguments.length < 2 || !babelTypes.isArrayExpression(node.arguments[1])) {
                return;
            }


            const callback = node.arguments[0];
            if (!babelTypes.isArrowFunctionExpression(callback) && !babelTypes.isFunctionExpression(callback)) {
                return;
            }


            const usedIdentifiers = new Set();
            babelTraverse(callback, {
                Identifier(idPath) {
                    const idNode = idPath.node;
                    const idName = idNode.name;


                    if (idPath.scope.hasBinding(idName) ||
                        ['document', 'window', 'console', 'Math', 'JSON', 'parseInt', 'undefined'].includes(idName)) {
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
                }
            }, path.scope);


            const dependencies = Array.from(usedIdentifiers).sort();
            const newDepsArray = babelTypes.arrayExpression(
                dependencies.map(dep => babelTypes.identifier(dep))
            );

            node.arguments[1] = newDepsArray;
        }
    });
}

/**
 * Extract components from large components
 */
function extractComponent(ast, options) {



    return;
}

/**
 * Improve JSX style
 */
function improveJsxStyle(ast, options) {
    babelTraverse(ast, {
        JSXAttribute(path) {

            if (path.node.name.name === 'style' && babelTypes.isJSXExpressionContainer(path.node.value)) {

            }
        },
        JSXElement(path) {

            if (path.node.children.length > 3) {

            }
        }
    });
}
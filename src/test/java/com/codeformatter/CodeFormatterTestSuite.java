package com.codeformatter;

import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Comprehensive test suite for the Advanced Code Formatter.
 */
@Suite
@SuiteDisplayName("Advanced Code Formatter Test Suite")
@SelectPackages({
        "com.codeformatter.core",
        "com.codeformatter.plugins.spring",
        "com.codeformatter.plugins.react",
        "com.codeformatter.plugins.spring.analyzers",
        "com.codeformatter.cli"
})
public class CodeFormatterTestSuite {

}
package io.reggraeton.model;

import java.util.List;

/**
 * A Cucumber hook ({@code @Before}, {@code @After}, {@code @BeforeStep}, {@code @AfterStep},
 * {@code @BeforeAll}, {@code @AfterAll}).
 *
 * @param tagExpression tag expression limiting the hook, empty when it applies to every scenario
 * @param order         Cucumber hook order (default 10000)
 */
public record Hook(
        String id,
        String phase,
        String tagExpression,
        int order,
        String style,
        String className,
        String methodName,
        List<Parameter> parameters,
        String file,
        int line) {

    public static final int DEFAULT_ORDER = 10000;
}

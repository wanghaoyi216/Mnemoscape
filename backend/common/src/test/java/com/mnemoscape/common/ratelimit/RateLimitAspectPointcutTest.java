package com.mnemoscape.common.ratelimit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitAspectPointcutTest {

    @Test
    void pointcutMatchesMethodAnnotationsWithoutBindingAdviceParameter() throws Exception {
        AspectJExpressionPointcut pointcut = rateLimitPointcut();

        assertThat(pointcut.matches(
                MethodLimitedController.class.getDeclaredMethod("limited"),
                MethodLimitedController.class)).isTrue();
        assertThat(pointcut.matches(
                MethodLimitedController.class.getDeclaredMethod("plain"),
                MethodLimitedController.class)).isFalse();
    }

    @Test
    void pointcutMatchesClassAnnotationsWithoutBindingAdviceParameter() throws Exception {
        AspectJExpressionPointcut pointcut = rateLimitPointcut();

        assertThat(pointcut.matches(
                ClassLimitedController.class.getDeclaredMethod("limitedByClass"),
                ClassLimitedController.class)).isTrue();
    }

    private static AspectJExpressionPointcut rateLimitPointcut() throws Exception {
        Method advice = RateLimitAspect.class.getMethod("around", ProceedingJoinPoint.class);
        AspectJExpressionPointcut pointcut = new AspectJExpressionPointcut();
        pointcut.setExpression(advice.getAnnotation(Around.class).value());
        return pointcut;
    }

    static class MethodLimitedController {
        @RateLimit(limit = 1, windowSeconds = 60)
        void limited() {
        }

        void plain() {
        }
    }

    @RateLimit(limit = 1, windowSeconds = 60)
    static class ClassLimitedController {
        void limitedByClass() {
        }
    }
}

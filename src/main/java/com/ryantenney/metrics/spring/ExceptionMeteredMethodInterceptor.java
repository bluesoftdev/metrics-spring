package com.ryantenney.metrics.spring;

import com.yammer.metrics.annotation.ExceptionMetered;
import com.yammer.metrics.core.Meter;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.MetricsRegistry;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ReflectionUtils.MethodCallback;
import org.springframework.util.ReflectionUtils.MethodFilter;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

class ExceptionMeteredMethodInterceptor implements MethodInterceptor, MethodCallback, Ordered {

	private static final Logger log = LoggerFactory.getLogger(ExceptionMeteredMethodInterceptor.class);

	private static final MethodFilter filter = new AnnotationFilter(ExceptionMetered.class);

	private final MetricsRegistry metrics;
	private final Class<?> targetClass;
	private final Map<String, Meter> meters;
	private final Map<String, Class<? extends Throwable>> causes;
	private final String scope;

	public ExceptionMeteredMethodInterceptor(final MetricsRegistry metrics, final Class<?> targetClass, final String scope) {
		this.metrics = metrics;
		this.targetClass = targetClass;
		this.meters = new HashMap<String, Meter>();
		this.causes = new HashMap<String, Class<? extends Throwable>>();
		this.scope = scope;

		log.debug("Creating method interceptor for class {}", targetClass.getCanonicalName());
		log.debug("Scanning for @ExceptionMetered annotated methods");

		ReflectionUtils.doWithMethods(targetClass, this, filter);
	}

	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		try {
			return invocation.proceed();
		} catch (Throwable t) {
			final String name = invocation.getMethod().getName();
			final Class<?> cause = causes.get(name);
			if (cause != null && cause.isAssignableFrom(t.getClass())) {
				// it may be safe to infer that `meter` is non-null if `cause` is non-null
				Meter meter = meters.get(name);
				if (meter != null) {
					meter.mark();
				}
			}
			throw t;
		}
	}

	@Override
	public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
		final ExceptionMetered annotation = method.getAnnotation(ExceptionMetered.class);
		final MetricName metricName = Util.forExceptionMeteredMethod(targetClass, method, annotation, scope);
		final Meter meter = metrics.newMeter(metricName, annotation.eventType(), annotation.rateUnit());

		meters.put(method.getName(), meter);
		causes.put(method.getName(), annotation.cause());

		log.debug("Created metric {} for method {}", metricName, method.getName());
	}

	@Override
	public int getOrder() {
		return HIGHEST_PRECEDENCE;
	}

}

package de.oose.e4springdi.internal;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.e4.core.di.suppliers.ExtendedObjectSupplier;
import org.eclipse.e4.core.di.suppliers.IObjectDescriptor;
import org.eclipse.e4.core.di.suppliers.IRequestor;
import org.eclipse.gemini.blueprint.context.event.OsgiBundleApplicationContextEvent;
import org.eclipse.gemini.blueprint.context.event.OsgiBundleApplicationContextListener;
import org.eclipse.gemini.blueprint.context.event.OsgiBundleContextClosedEvent;
import org.eclipse.gemini.blueprint.context.event.OsgiBundleContextRefreshedEvent;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.springframework.context.ApplicationContext;

import de.oose.e4springdi.SpringInject;

/**
 * @author FelixH
 *
 */
@SuppressWarnings({ "restriction", "rawtypes" })
public class SpringObjectSupplier extends ExtendedObjectSupplier implements
		OsgiBundleApplicationContextListener {

	public static final String APP_CONTEXT_SERVICE_NAME = ApplicationContext.class
			.getName();

	public static final String CONTEXT_SOURCE_BUNDLE_PROPERTY = "org.springframework.context.service.name";

	private final Map<String, ApplicationContext> cachedContexts = new ConcurrentHashMap<String, ApplicationContext>();
	private final Map<String, List<IRequestor>> trackedRequestorsForBundle = new ConcurrentHashMap<String, List<IRequestor>>();

	@Override
	public Object get(IObjectDescriptor descriptor, IRequestor requestor,
			boolean track, boolean group) {

		String symbolicNameOfTargetBundle = FrameworkUtil.getBundle(
				requestor.getRequestingObjectClass()).getSymbolicName();

		setUpTracking(symbolicNameOfTargetBundle, requestor, track);

		final ApplicationContext applicationContext = findApplicationContext(symbolicNameOfTargetBundle);

		if (applicationContext != null) {
			return getFromContext(descriptor, applicationContext);
		}

		return null;
	}
	
	// synchronize to make sure no requestor is lost when one starts tracking
	// while the the other one is
	// stopping tracking for the same bundle and concurrently....
	private synchronized void setUpTracking(String symbolicName,
			IRequestor requestor, boolean track) {
		if (track) {
			List<IRequestor> list = trackedRequestorsForBundle
					.get(symbolicName);
			if (list == null) {
				list = new CopyOnWriteArrayList<IRequestor>();
				trackedRequestorsForBundle.put(symbolicName, list);
			}
			list.add(requestor);
		} else {
			List<IRequestor> list = trackedRequestorsForBundle
					.get(symbolicName);
			if (list != null) {

				list.remove(requestor);
				if (list.isEmpty()) {
					trackedRequestorsForBundle.remove(symbolicName);
				}
			}
		}
	}


	private ApplicationContext findApplicationContext(
			String symbolicNameOfTargetBundle) {

		ApplicationContext applicationContext = cachedContexts
				.get(symbolicNameOfTargetBundle);

		if (applicationContext == null) {
			applicationContext = getApplicationContextService(symbolicNameOfTargetBundle);

			if (applicationContext != null) {
				cachedContexts.put(symbolicNameOfTargetBundle,
						applicationContext);
			}
		}
		return applicationContext;
	}

	private ApplicationContext getApplicationContextService(
			String symbolicNameOfTargetBundle) {

		final BundleContext localBundleContext = FrameworkUtil.getBundle(
				SpringObjectSupplier.class).getBundleContext();

		final String filter = '(' + CONTEXT_SOURCE_BUNDLE_PROPERTY + '='
				+ symbolicNameOfTargetBundle + ')';
		try {
			final ServiceReference<?>[] refs = localBundleContext
					.getServiceReferences(APP_CONTEXT_SERVICE_NAME, filter);
			if (refs != null && refs.length > 0) {

				// Explicitly sort by ranking if more than one context is
				// found
				// (what is weird ...)
				if (refs.length > 1) {
					Arrays.sort(refs, Collections.reverseOrder());
				}

				return (ApplicationContext) localBundleContext
						.getService(refs[0]);
			}

		} catch (InvalidSyntaxException e) {
			// should not happen - we tested the line above
		}

		return null;
	}

	private Object getFromContext(final IObjectDescriptor descriptor,
			final ApplicationContext context) {

		final String beanName = descriptor.getQualifier(SpringInject.class)
				.beanName();

		if (!beanName.trim().isEmpty()) {
			return context.getBean(beanName);
		}

		final Type desiredType = descriptor.getDesiredType();

		if (desiredType instanceof Class) {
			return context.getBean((Class) desiredType);
		}

		return null;
	}



	@Override
	public void onOsgiApplicationEvent(OsgiBundleApplicationContextEvent event) {

		String refreshedBundleName = event.getBundle().getSymbolicName();

		if (event instanceof OsgiBundleContextRefreshedEvent) {
			cachedContexts.remove(refreshedBundleName);
			List<IRequestor> list = trackedRequestorsForBundle
					.get(refreshedBundleName);
			if (list != null) {
				for (IRequestor iRequestor : list) {
					if (iRequestor.isValid()) {
						iRequestor.resolveArguments(false);
						iRequestor.execute();
					}
				}
			}
		} else if (event instanceof OsgiBundleContextClosedEvent) {
			cachedContexts.remove(refreshedBundleName);
		}
	}

}

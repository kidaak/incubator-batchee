/*
 * 
 * Copyright 2012,2013 International Business Machines Corp.
 * 
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. Licensed under the Apache License, 
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.batchee.container.services;

import org.apache.batchee.container.exception.BatchContainerRuntimeException;
import org.apache.batchee.container.exception.BatchContainerServiceException;
import org.apache.batchee.container.services.callback.SimpleJobExecutionCallbackService;
import org.apache.batchee.container.services.data.DefaultDataRepresentationService;
import org.apache.batchee.container.services.executor.DefaultThreadPoolService;
import org.apache.batchee.container.services.factory.CDIBatchArtifactFactory;
import org.apache.batchee.container.services.factory.DefaultBatchArtifactFactory;
import org.apache.batchee.container.services.kernel.DefaultBatchKernel;
import org.apache.batchee.container.services.loader.DefaultJobXMLLoaderService;
import org.apache.batchee.container.services.locator.SingletonLocator;
import org.apache.batchee.container.services.persistence.MemoryPersistenceManagerService;
import org.apache.batchee.container.services.security.DefaultSecurityService;
import org.apache.batchee.container.services.status.DefaultJobStatusManager;
import org.apache.batchee.container.services.transaction.DefaultBatchTransactionService;
import org.apache.batchee.container.util.BatchContainerConstants;
import org.apache.batchee.spi.BatchArtifactFactory;
import org.apache.batchee.spi.BatchService;
import org.apache.batchee.spi.BatchThreadPoolService;
import org.apache.batchee.spi.DataRepresentationService;
import org.apache.batchee.spi.JobExecutionCallbackService;
import org.apache.batchee.spi.JobXMLLoaderService;
import org.apache.batchee.spi.PersistenceManagerService;
import org.apache.batchee.spi.SecurityService;
import org.apache.batchee.spi.TransactionManagementService;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class ServicesManager implements BatchContainerConstants {
    private final static Logger LOGGER = Logger.getLogger(ServicesManager.class.getName());

    private static final String SERVICES_CONFIGURATION_FILE = "batchee.properties";

    // Use class names instead of Class objects to not drag in any dependencies and to easily interact with properties
    private static final Map<String, String> SERVICE_IMPL_CLASS_NAMES = new ConcurrentHashMap<String, String>();
    static {
        SERVICE_IMPL_CLASS_NAMES.put(TransactionManagementService.class.getName(), DefaultBatchTransactionService.class.getName());
        SERVICE_IMPL_CLASS_NAMES.put(PersistenceManagerService.class.getName(), MemoryPersistenceManagerService.class.getName());
        SERVICE_IMPL_CLASS_NAMES.put(JobStatusManagerService.class.getName(), DefaultJobStatusManager.class.getName());
        SERVICE_IMPL_CLASS_NAMES.put(BatchThreadPoolService.class.getName(), DefaultThreadPoolService.class.getName());
        SERVICE_IMPL_CLASS_NAMES.put(BatchKernelService.class.getName(), DefaultBatchKernel.class.getName());
        SERVICE_IMPL_CLASS_NAMES.put(JobXMLLoaderService.class.getName(), DefaultJobXMLLoaderService.class.getName());
        SERVICE_IMPL_CLASS_NAMES.put(SecurityService.class.getName(), DefaultSecurityService.class.getName());
        SERVICE_IMPL_CLASS_NAMES.put(JobExecutionCallbackService.class.getName(), SimpleJobExecutionCallbackService.class.getName());
        SERVICE_IMPL_CLASS_NAMES.put(DataRepresentationService.class.getName(), DefaultDataRepresentationService.class.getName());
        try {
            Thread.currentThread().getContextClassLoader().loadClass("javax.enterprise.inject.spi.BeanManager");
            SERVICE_IMPL_CLASS_NAMES.put(BatchArtifactFactory.class.getName(), CDIBatchArtifactFactory.class.getName());
        } catch (final Throwable th) {
            SERVICE_IMPL_CLASS_NAMES.put(BatchArtifactFactory.class.getName(), DefaultBatchArtifactFactory.class.getName());
        }

        setServicesManagerLocator(SingletonLocator.INSTANCE); // default init
    }

    private static ServicesManagerLocator servicesManagerLocator;

    private ClassLoader loader = null;

    // designed to be used from app or a server
    public static void setServicesManagerLocator(final ServicesManagerLocator locator) {
        servicesManagerLocator = locator;
    }

    public static ServicesManager find() {
        return servicesManagerLocator.find();
    }

    public static String value(final String key, final String defaultValue) {
        return servicesManagerLocator.find().batchRuntimeConfig.getProperty(key, defaultValue);
    }

    // Declared 'volatile' to allow use in double-checked locking.  This 'isInited'
    // refers to whether the configuration has been hardened and possibly the
    // first service impl loaded, not whether the instance has merely been instantiated.
    private final byte[] isInitedLock = new byte[0];
    private volatile boolean isInited = false;

    private Properties batchRuntimeConfig;
    private boolean logServices;

    // Registry of all current services
    private final ConcurrentHashMap<String, BatchService> serviceRegistry = new ConcurrentHashMap<String, BatchService>();

    /**
     * Init doesn't actually load the service impls, which are still loaded lazily.   What it does is it
     * hardens the config.  This is necessary since the batch runtime by and large is not dynamically
     * configurable, (e.g. via MBeans).  Things like the database config used by the batch runtime's
     * persistent store are hardened then, as are the names of the service impls to use.
     */
    public void init(final Properties props) {
        // Use double-checked locking with volatile.
        if (!isInited) {
            synchronized (isInitedLock) {
                if (!isInited) {
                    batchRuntimeConfig = new Properties();

                    batchRuntimeConfig.putAll(SERVICE_IMPL_CLASS_NAMES); // defaults

                    loader = Thread.currentThread().getContextClassLoader();

                    // file in the classloader
                    final InputStream batchServicesListInputStream = loader.getResourceAsStream(SERVICES_CONFIGURATION_FILE);
                    if (batchServicesListInputStream != null) {
                        try {
                            batchRuntimeConfig.load(batchServicesListInputStream);
                        } catch (final Exception e) {
                            LOGGER.config("Error loading " + SERVICES_CONFIGURATION_FILE + " Exception=" + e.toString());
                        } finally {
                            try {
                                batchServicesListInputStream.close();
                            } catch (final IOException e) {
                                // no-op
                            }
                        }
                    }

                    // API overriding
                    if (props != null) {
                        batchRuntimeConfig.putAll(props);
                    }

                    // JVM instance overriding
                    batchRuntimeConfig.putAll(System.getProperties());

                    logServices = Boolean.parseBoolean(batchRuntimeConfig.getProperty("batchee.service-manager.log", "false"));

                    isInited = Boolean.TRUE;
                }
            }
        }
    }

    public <T extends BatchService> T service(final Class<T> clazz) throws BatchContainerServiceException {
        T service = clazz.cast(serviceRegistry.get(clazz.getName()));
        if (service == null) {
            // Probably don't want to be loading two on two different threads so lock the whole table.
            synchronized (serviceRegistry) {
                service = clazz.cast(serviceRegistry.get(clazz.getName()));
                if (service == null) {
                    service = loadService(clazz);
                    service.init(batchRuntimeConfig);
                    serviceRegistry.putIfAbsent(clazz.getName(), service);
                }
            }
        }
        return service;
    }

    private <T extends BatchService> T loadService(final Class<T> serviceType) {
        final Object existing = batchRuntimeConfig.get(serviceType.getName());
        if (serviceType.isInstance(existing)) {
            return serviceType.cast(existing);
        }

        T service = null;
        String className = batchRuntimeConfig.getProperty(serviceType.getSimpleName()); // short name first
        try {
            if (className != null) {
                service = load(serviceType, className);
            } else {
                className = String.class.cast(existing);
                if (className != null) {
                    service = load(serviceType, className);
                }
            }
        } catch (final Throwable e1) {
            handleBatchContainerRuntimeException(e1);
            throw new IllegalArgumentException("Could not instantiate service " + className + " due to exception: " + e1);
        }

        if (service == null) {
            throw new BatchContainerRuntimeException("Instantiate of service=: " + className + " returned null. Aborting...");
        }

        if (logServices) {
            LOGGER.info("Using " + service + " (" + className + ") as " + serviceType.getName());
        }

        return service;
    }

    private <T> T load(final Class<T> expected, final String className) throws Exception {
        Class<?> cls = null;

        try {
            cls = getLoader().loadClass(className);
        } catch (Exception e) {
            handleBatchContainerRuntimeException(e);
            throw new BatchContainerRuntimeException("Could not load Service class " + className + ". Make sure it exists", e);
        }
        Throwable problem = null;
        try {
            final Constructor<?> constructor = cls.getConstructor(ServicesManager.class);
            return expected.cast(constructor.newInstance(this));
        } catch (final Throwable th) {
            handleBatchContainerRuntimeException(th);
            // remember the first problem and try no arg constructor
            problem = th;
        }
        try {
            if (cls.getConstructor() != null) {
                return expected.cast(cls.newInstance());
            }
        } catch (Throwable th) {
            handleBatchContainerRuntimeException(th);
            // default ct works neither, lets report the original problem
        }
        throw new BatchContainerRuntimeException("Service class " + className + " cannnot be loaded", problem);
    }

    /**
     * prevent BatchContainerRuntimeExceptions to get swallowed
     */
    private void handleBatchContainerRuntimeException(Throwable e) throws BatchContainerRuntimeException {
        if (e instanceof BatchContainerRuntimeException) {
            throw (BatchContainerRuntimeException) e;
        }
        if (e instanceof InvocationTargetException) {
            InvocationTargetException ite = (InvocationTargetException) e;
            if (ite.getCause() instanceof BatchContainerRuntimeException) {
                throw (BatchContainerRuntimeException) ite.getCause();
            }
        }
        // else all is fine
    }

    private ClassLoader getLoader() {
        if (loader != null) {
            return loader;
        }
        return Thread.currentThread().getContextClassLoader();
    }
}


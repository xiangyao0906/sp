package chao.java.tools.servicepool.combine;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import chao.android.tools.interceptor.Interceptor;
import chao.android.tools.interceptor.OnInvoke;
import chao.java.tools.servicepool.IService;
import chao.java.tools.servicepool.IServiceFactories;
import chao.java.tools.servicepool.IServiceFactory;
import chao.java.tools.servicepool.Logger;
import chao.java.tools.servicepool.NoOpInstance;
import chao.java.tools.servicepool.ServicePool;
import chao.java.tools.servicepool.ServiceProxy;
import chao.java.tools.servicepool.thirdparty.CancelableCountDownLatch;


/**
 * @author luqin
 * @since 2019-09-29
 */
public class CombineManager {


    private static final String COMBINE_METHOD_GET = "get";

    private static final String COMBINE_METHOD_SIZE = "add";

    private static final String COMBINE_METHOD_ITERATOR = "iterator";


    private Logger logger = new Logger();

    private HashMap<Class, List<ServiceProxy>> combinedCache = new HashMap<>();


    public CombineManager() {
    }

    public <T extends IService> T getCombineService(final Class<T> serviceClass, final List<IServiceFactories> factories) {
        if (serviceClass == null) {
            throw new IllegalArgumentException("argument 'serviceClass' should not be null.");
        }
        if (!serviceClass.isInterface()) {
            throw new IllegalArgumentException("argument 'serviceClass' should be a interface class.");
        }
        return Interceptor.of(null, serviceClass)
                .intercepted(true)
                .interfaces(CombineService.class, Iterable.class)
                .invoke(new OnInvoke<T>() {

                    @Override
                    public Object onInvoke(T source, final Method method, final Object[] args) {

                        List<ServiceProxy> proxies = _getCombinedServices(serviceClass, factories);

                        //拦截器按优先级排序
                        Collections.sort(proxies, new Comparator<ServiceProxy>() {
                            @Override
                            public int compare(ServiceProxy s1, ServiceProxy s2) {
                                return s2.priority() - s1.priority();
                            }
                        });

                        if (COMBINE_METHOD_SIZE.equals(method.getName()) && (args == null || args.length == 0)) {
                            //Iterable.size()
                            return proxies.size();
                        } else if (COMBINE_METHOD_GET.equals(method.getName()) && args != null && args.length == 1 && args[0] instanceof Integer) {
                            //Iterable.get(int index)
                            return proxies.get((Integer) args[0]);
                        } else if (COMBINE_METHOD_ITERATOR.equals(method.getName()) && (args == null || args.length == 0)) {
                            //Iterable.iterator()
                            return proxies.iterator();
                        }

                        CombineStrategy combineStrategy = null;
                        if (serviceClass != CombineStrategy.class) {
                            combineStrategy = ServicePool.getCombineService(CombineStrategy.class);
                        }

                        if(combineStrategy != null
                                && !(combineStrategy instanceof NoOpInstance)
                                && combineStrategy.filter(serviceClass, method, args)
                                && combineStrategy.invoke(proxies, serviceClass, method, args)) {
                            return null;
                        }

                        Object result = null;
                        //默认按优先级顺序依次执行
                        for (ServiceProxy proxy : proxies) {
                            try {
                                result = method.invoke(proxy.getService(), args);
                            } catch (Throwable e) {
                                throw new CombineException(e.getMessage(), e);
                            }
                        }
                        return result;
                    }
                }).newInstance();
    }

    private void execute(List<ServiceProxy> proxies, int index, CancelableCountDownLatch counter, Method method, Object[] args) {
        if (index >= proxies.size()) {
            return;
        }
        ServiceProxy proxy = proxies.get(index);
        try {
            method.invoke(proxy.getService(), args);
        } catch (Throwable e) {
            logger.log("Combine service execution failed!!! " + e.getMessage());
        }
    }

    private List<ServiceProxy> _getCombinedServices(Class<?> serviceClass, List<IServiceFactories> factoriesList) {
        List<ServiceProxy> proxies = combinedCache.get(serviceClass);
        if (proxies != null) {
            return proxies;
        }
        proxies = new ArrayList<>();
        combinedCache.put(serviceClass, proxies);
        for (IServiceFactories factories: factoriesList) {
            String name = serviceClass.getName();
            int last = name.lastIndexOf('.');
            if (last == -1) {
                continue;
            }
            String pkgName = name.substring(0, last);
            IServiceFactory factory = factories.getServiceFactory(pkgName);
            if (factory == null) {
                continue;
            }
            Set<ServiceProxy> factoryProxies = factory.createServiceProxies(serviceClass);
            if (factoryProxies == null) {
                factoryProxies = Collections.emptySet();
            }
            proxies.addAll(factoryProxies);
        }
        return proxies;
    }


}

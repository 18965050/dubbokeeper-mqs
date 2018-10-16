/**
 * Project: dubbo.registry.console-2.2.0-SNAPSHOT
 *
 * File Created at Mar 21, 2012
 * $Id: RegistryServerSync.java 182143 2012-06-27 03:25:50Z tony.chenl $
 *
 * Copyright 1999-2100 Alibaba.com Corporation Limited.
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of
 * Alibaba Company. ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Alibaba.com.
 */
package com.dubboclub.dk.admin.sync;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.RegistryService;
import com.dubboclub.dk.admin.sync.util.SyncUtils;
import com.dubboclub.dk.alarm.AlarmService;

/**
 * @author bieber
 */
public class RegistryServerSync implements InitializingBean, DisposableBean, NotifyListener {

	private static final Logger logger = LoggerFactory.getLogger(RegistryServerSync.class);

	private static final URL SUBSCRIBE = new URL(Constants.ADMIN_PROTOCOL, NetUtils.getLocalHost(), 0, "",
			Constants.INTERFACE_KEY, Constants.ANY_VALUE,
			Constants.GROUP_KEY, Constants.ANY_VALUE,
			Constants.VERSION_KEY, Constants.ANY_VALUE,
			Constants.CLASSIFIER_KEY, Constants.ANY_VALUE,
			Constants.CATEGORY_KEY, Constants.PROVIDERS_CATEGORY + ","
			+ Constants.CONSUMERS_CATEGORY + ","
			+ Constants.ROUTERS_CATEGORY + ","
			+ Constants.CONFIGURATORS_CATEGORY,
			Constants.ENABLED_KEY, Constants.ANY_VALUE,
			Constants.CHECK_KEY, String.valueOf(false));

	private static final AtomicLong ID = new AtomicLong();

	private RegistryService registryService;

	private AlarmService alarmService;

	// ConcurrentMap<category, ConcurrentMap<servicename, Map<Long, URL>>>
	private final ConcurrentMap<String, ConcurrentMap<String, Map<Long, URL>>> registryCache = new ConcurrentHashMap<String, ConcurrentMap<String, Map<Long, URL>>>();

	private final ConcurrentMap<String, Set<String>> serviceCache = new ConcurrentHashMap<String, Set<String>>();

	public ConcurrentMap<String, ConcurrentMap<String, Map<Long, URL>>> getRegistryCache(){
		return registryCache;
	}

	public void afterPropertiesSet() throws Exception {
		new Thread(new Runnable() {
			@Override
			public void run() {
				logger.info("Init Dubbo Admin Sync Cache...");
				registryService.subscribe(SUBSCRIBE, RegistryServerSync.this);
			}
		},"SUBSCRIBE-ADMIN-THREAD").start();
	}

	public void update(URL oldURL,URL newURL){
		registryService.unregister(oldURL);
		registryService.register(newURL);
	}

	public void unregister(URL url){
		registryService.unregister(url);
	}

	public void register(URL url){
		registryService.register(url);
	}

	public void destroy() throws Exception {
		registryService.unsubscribe(SUBSCRIBE, this);
	}


	// 收到的通知对于 ，同一种类型数据（override、subcribe、route、其它是Provider），同一个服务的数据是全量的
	public void notify(List<URL> urls) {
		if(urls == null || urls.isEmpty()) {
			return;
		}
		boolean cleaned = false;
		final Map<String, Map<String, Map<Long, URL>>> categories = new HashMap<String, Map<String, Map<Long, URL>>>();
		for(URL url : urls) {
			String category = url.getParameter(Constants.CATEGORY_KEY, Constants.PROVIDERS_CATEGORY);
			ConcurrentMap<String, Map<Long, URL>> oldServices = registryCache.get(category);
			if (!cleaned) {
				// 这里清理了此服务的所有数据，如果订阅部分group version数据，这里需要做区分
				// 事实上由于注册中心对通知数据已做了过滤这里不做区分也是可以的
				// 注意：empty协议时interface为*，这里必须用path来获取服务，总感觉是个BUG
				Set<String> serviceKeys = serviceCache.get(url.getPath());
				if (serviceKeys != null && oldServices != null) {
					for (String serviceKey : serviceKeys) {
						oldServices.remove(serviceKey);
					}
				}
				cleaned = true;
			}
			if(Constants.EMPTY_PROTOCOL.equalsIgnoreCase(url.getProtocol())) { // 注意：empty协议的group和version为*
				if(oldServices != null) {
					alarmService.alarmHandle(url);
				}
			} else {
				Map<String, Map<Long, URL>> newServices = categories.get(category);
				if(newServices == null) {
					newServices = new HashMap<String, Map<Long,URL>>();
					categories.put(category, newServices);
				}
				String service = SyncUtils.generateServiceKey(url);
				Set<String> serviceKeys = serviceCache.get(url.getServiceInterface());
				if (serviceKeys == null) {
					serviceKeys = new HashSet<String>();
					serviceCache.put(url.getServiceInterface(), serviceKeys);
				}
				serviceKeys.add(service);
				Map<Long, URL> ids = newServices.get(service);
				if(ids == null) {
					ids = new HashMap<Long, URL>();
					newServices.put(service, ids);
				}
				ids.put(Long.valueOf(md5(url.toFullString()).hashCode()), url);
			}
		}
		for(Map.Entry<String, Map<String, Map<Long, URL>>> categoryEntry : categories.entrySet()) {
			String category = categoryEntry.getKey();
			ConcurrentMap<String, Map<Long, URL>> services = registryCache.get(category);
			if(services == null) {
				services = new ConcurrentHashMap<String, Map<Long,URL>>();
				registryCache.put(category, services);
			}
			services.putAll(categoryEntry.getValue());
		}

	}
	public void setRegistryService(RegistryService registryService) {
		this.registryService = registryService;
	}
	public void setAlarmService(AlarmService alarmService) {
		this.alarmService = alarmService;
	}

	public static String md5(String src){
		try {
			// 生成一个MD5加密计算摘要
			MessageDigest md = MessageDigest.getInstance("MD5");
			// 计算md5函数
			md.update(src.getBytes());
			// digest()最后确定返回md5 hash值，返回值为8为字符串。因为md5 hash值是16位的hex值，实际上就是8位的字符
			// BigInteger函数则将8位的字符串转换成16位hex值，用字符串来表示；得到字符串形式的hash值
			return new BigInteger(1, md.digest()).toString(16);
		} catch (Exception e) {
			throw new RuntimeException("md5 failure");
		}
	}
}
    

/**
 * copyright @ dangdang.com 2015
 */
package com.dangdang.hamal.topology;

import java.io.IOException;
import java.util.Map;

import com.dangdang.hamal.conf.ListenerConf;
import com.dangdang.hamal.conf.ListenerConfLoader;
import com.dangdang.hamal.conf.LogOriginConf;
import com.dangdang.hamal.conf.MysqlConf;
import com.dangdang.hamal.conf.TopologyConf;
import com.dangdang.hamal.conf.TopologyConfLoader;
import com.dangdang.hamal.conf.TopologyConf.ListenerParam;
import com.dangdang.hamal.mysql.connector.MysqlConnector;
import com.dangdang.hamal.mysql.core.BinaryLogClient;
import com.dangdang.hamal.mysql.core.BinaryLogFileReader;
import com.dangdang.hamal.mysql.core.Listeners;

/**
 * �������
 * @author zhangkunjs
 *
 */
public class Topology {

	private BinaryLogClient client;

	private BinaryLogFileReader fileReader;

	private LogOriginConf orginConf ;
	private Map<String,ListenerConf> listeners;
	private Map<String, ListenerParam> listenerParams;

	public LogOriginConf getSourceConf() {
		return orginConf;
	}

	public void setSourceConf(LogOriginConf sourceConf) {
		this.orginConf = sourceConf;
	}

	public Map<String, ListenerConf> getListeners() {
		return listeners;
	}

	public void setListeners(Map<String, ListenerConf> listeners) {
		this.listeners = listeners;
	}	

	public Map<String, ListenerParam> getListenerParams() {
		return listenerParams;
	}

	public void setListenerParams(Map<String, ListenerParam> listenerParams) {
		this.listenerParams = listenerParams;
	}

	private String getSourceType()
	{
		return orginConf.getType();
	}

	public void start()
	{
		if(getSourceType().equals(LogOriginConf.LOT_MYSQL))
		{
			MysqlConf mysqlConf=(MysqlConf)orginConf;
			client = new BinaryLogClient(mysqlConf.getDbhost(),mysqlConf.getPort(),mysqlConf.getUsername(),mysqlConf.getPassword());
			MysqlConnector.createSingleton(mysqlConf.getDbhost(),mysqlConf.getPort(),mysqlConf.getUsername(),mysqlConf.getPassword());
		}
		else if(getSourceType().equals(LogOriginConf.LOT_FILE))
		{
			
		}
		registerListeners();
		connect();
	}

	private void connect() {
		if(client!=null)
			try {
				client.connect();
			} catch (IOException e) {
				e.printStackTrace();
			}
	}

	private void registerListeners() {
		if(client!=null)
			for(String name: listeners.keySet())
			{
				ListenerConf conf=listeners.get(name);
				String listenerClazz=conf.getClazz();
				try {
					Listeners.EventListener listener=(Listeners.EventListener)Class.forName(listenerClazz).newInstance();
					if(this.listenerParams.containsKey(name))
					{
						listener.onStart(this.listenerParams.get(name));
					}
					client.registerEventListener(listener);
				} catch (InstantiationException e) {
					e.printStackTrace();
				} catch (IllegalAccessException e) {
					e.printStackTrace();
				} catch (ClassNotFoundException e) {
					e.printStackTrace();
				}
			}
	}

	public void stop()
	{
		if(client!=null)
		{
			try {
				this.client.disconnect();
				MysqlConnector.getSingleton().disconnect();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public static void main(String[] args) {

		Map<String,ListenerConf> listeners=ListenerConfLoader.loadListenerConfig();
		for(String key:listeners.keySet())
		{
			System.out.println(key+":"+listeners.get(key).getClazz());
		}
		TopologyConf topologyConf=TopologyConfLoader.loadTopologyConfig();
		Map<String, ListenerParam> listenerParams=topologyConf.getListenerParams();
		LogOriginConf sourceConf=topologyConf.getSourceConf();
		Topology topology=new Topology();
		topology.setSourceConf(sourceConf);
		topology.setListeners(listeners);
		topology.setListenerParams(listenerParams);
		topology.start();
	}

}

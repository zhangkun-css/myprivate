package com.dangdang.hamal.event.filter;

import com.dangdang.hamal.mysql.core.event.Event;

/**
 * ���ݿ�ṹ�仯������ 
 * ���� �޸ı������� 
 * @author zhangkunjs
 *
 */
public class DMLFilter implements EventFilter{

	@Override
	public void initParams(String[] params) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public boolean accept(Event event) {
		// TODO Auto-generated method stub
		return false;
	}

}


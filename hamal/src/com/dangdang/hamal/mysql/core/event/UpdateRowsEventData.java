/*
 *  
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dangdang.hamal.mysql.core.event;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

/**
 * @author  
 */
public class UpdateRowsEventData implements EventData {

	private long tableId;
	private BitSet includedColumnsBeforeUpdate;
	private BitSet includedColumns;
	/**
	 * @see com.dangdang.hamal.mysql.core.event.parser.AbstractRowsEventDataDeserializer
	 */
	private List<Map.Entry<Serializable[], Serializable[]>> rows;

	public long getTableId() {
		return tableId;
	}

	public void setTableId(long tableId) {
		this.tableId = tableId;
	}

	public BitSet getIncludedColumnsBeforeUpdate() {
		return includedColumnsBeforeUpdate;
	}

	public void setIncludedColumnsBeforeUpdate(BitSet includedColumnsBeforeUpdate) {
		this.includedColumnsBeforeUpdate = includedColumnsBeforeUpdate;
	}

	public BitSet getIncludedColumns() {
		return includedColumns;
	}

	public void setIncludedColumns(BitSet includedColumns) {
		this.includedColumns = includedColumns;
	}

	public List<Map.Entry<Serializable[], Serializable[]>> getRows() {
		return rows;
	}

	public void setRows(List<Map.Entry<Serializable[], Serializable[]>> rows) {
		this.rows = rows;
	}

	public List<Serializable[]> getUpdatedRows()
	{
		List<Serializable[]>  updatedRows=new ArrayList<Serializable[]> ();
		for (Map.Entry<Serializable[], Serializable[]> row : rows) {
			updatedRows.add(row.getValue());
		}
		return updatedRows;
	}

	public List<Integer[]> getUpdatedCoulumnIdxs()
	{
		List<Integer[]> updated_column_idxs=new ArrayList<Integer[]>();
		for (Map.Entry<Serializable[], Serializable[]> row : rows) {
			List<Integer> cloumn_idxs=new ArrayList<Integer>();
			Serializable[] before=row.getKey();
			Serializable[] after=row.getValue();
			for(int i=0;i<before.length;i++)
			{
				if(!equals(before[i],after[i]))
				{
					cloumn_idxs.add(i);
				}
			}
			Integer[] arr_columns= new Integer[cloumn_idxs.size()];
			cloumn_idxs.toArray(arr_columns);
			updated_column_idxs.add(arr_columns);
		}
		return updated_column_idxs;
	}

	private boolean equals(Serializable beforeCell,Serializable afterCell)
	{
		if(beforeCell==null&&afterCell==null )
		{
			return true;
		}
		if((beforeCell!=null&&afterCell==null)||(beforeCell==null&&afterCell!=null))
		{
			return false;	
		}
		if(beforeCell instanceof byte[])
		{
			byte[] beforeCellBuff=(byte[])beforeCell;
			byte[] afterCellBuff=(byte[])afterCell;
			return Arrays.equals(beforeCellBuff, afterCellBuff);
		}
		return beforeCell.equals(afterCell);
	}


	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		sb.append("UpdateRowsEventData");
		sb.append("{tableId=").append(tableId);
		sb.append(", includedColumnsBeforeUpdate=").append(includedColumnsBeforeUpdate);
		sb.append(", includedColumns=").append(includedColumns);
		sb.append(", rows=[");
		for (Map.Entry<Serializable[], Serializable[]> row : rows) {
			sb.append("\n    ").
			append("{before=").append(Arrays.toString(row.getKey())).
			append(", after=").append(Arrays.toString(row.getValue())).
			append("},");
		}
		if (!rows.isEmpty()) {
			sb.replace(sb.length() - 1, sb.length(), "\n");
		}
		sb.append("]}");
		return sb.toString();
	}
}

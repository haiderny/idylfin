/*
 * ====================================================
 * Copyright (C) 2013 by Idylwood Technologies, LLC. All rights reserved.
 *
 * Developed at Idylwood Technologies, LLC.
 * Permission to use, copy, modify, and distribute this
 * software is freely granted, provided that this notice 
 * is preserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * The License should have been distributed to you with the source tree.
 * If not, it can be found at
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * Author: Charles Cooper
 * Date: 2013
 * ====================================================
 */

package com.idylytics.yahoo;

import com.idylytics.*;

import java.io.*;
import java.net.URL;
import java.util.*;

import org.apache.commons.math3.stat.regression.*;

import de.erichseifert.gral.data.*;
import de.erichseifert.gral.data.filters.*;
import de.erichseifert.gral.data.filters.Filter.Mode;

import com.idylytics.utils.FinUtils;
import com.idylytics.utils.IOUtils;

public class YahooFinance
{
	final static public Date DEFAULT_START_DATE = new Date(20070101);
	Map<String,HistTable> mTables = new HashMap<String,HistTable>();
	Map<String,DivTable> mDividends = new HashMap<String,DivTable>();
	Map<String,SplitTable> mSplits = new HashMap<String,SplitTable>();
	Map<String,DivSplitTable> mDivSplits = new HashMap<String,DivSplitTable>();

	// Surprisingly useful class which contains two doubles and a date.
	// TODO refactor to extend Date
	public static class Pair {
		public Date date;
		public double first;
		public double second;
		public Pair() {}
		public Pair(Date date, double first, double second) {
			this.date = date; this.first = first; this.second = second;
		}
		// copy constructor
		public Pair(Pair other) {
			this(other.date,other.first,other.second);
		}

		@Override public String toString() { return date.toString() + ","+first +","+ second; }
	}
	public static class Single extends Date {
		public double data;
		public Single(Date date) { super(date); }
		public Single(Date date, double data) { this(date); this.data = data; }
		// copy constructor
		public Single(Single other) { this((Date)other,other.data); }
	}

	// TODO refactor to extend ArrayList
	public static class DivTable
	{
		public final String symbol;
		public final long dateAccessed; // in millis
		public final List<Single> data;
		public final boolean splitAdjusted;

		public DivTable(final String symbol,
				final long dateAccessed,
				final boolean splitAdjusted,
				final List<Single> data)
		{
			this.symbol = symbol; this.dateAccessed = dateAccessed;
			this.data = data; this.splitAdjusted = splitAdjusted;
		}
	}

	// TODO refactor to extend ArrayList
	public static class SplitTable
	{
		// TODO refactor so member variables are final
		public String symbol;
		public long dateAccessed; // in millis
		public List<Single> data; // 1:10 == 0.1, 3:2 == 1.5, etc.

		// TODO this is not thread safe! make the data structure immutable so that it is
		public List<Single> RunningRatios(Date startDate)
		{
			// Allocate new stuff all over the place! So glad this isn't C++.
			List<Single> ret = new ArrayList<Single>(this.data.size()+1);
			ret.add(new Single(startDate,1)); // start the ratio at zero.
			for (int i = 0; i < this.data.size(); ++i)
			{
				Single s = new Single(this.data.get(i));
				// recall that ret has an extra element at the beginning
				// so the indices are off by one.
				s.data *= ret.get(i).data;
				ret.add(s);
			}
			return ret;
		}
	}

	// TODO refactor so this is private
	public static class DivSplitTable extends ArrayList<Pair>
	{
		public DivSplitTable(int size) { super(size); }
		public DivSplitTable() { super(); }
		String symbol;
		long dateAccessed;
		Date startDate;
		Date endDate;
		int totalSize;
		int status;

		// TODO refactor so this is a method in an abstract Table class
		public DivSplitTable SubTable(Date startDate, Date endDate)
		{
			DivSplitTable ret = new DivSplitTable(this.size());
			for (Pair row : this)
				if (row.date.compareTo(startDate)>=0 && row.date.compareTo(endDate)<= 0)
					ret.add(new Pair(row)); // hammering malloc, baby

			ret.symbol = this.symbol; ret.dateAccessed = this.dateAccessed;
			ret.totalSize = -1; // undefined since i don't know what this represents
			ret.status = this.status;
			ret.startDate = this.startDate; ret.endDate = this.endDate;
			return ret;
		}

		// returns newly allocated thing.
		public DivTable DivTable()
		{
			final List<Single> data = new ArrayList<Single>();
			for (Pair p : this)
				if (0!=p.first)
					data.add(new Single(p.date,p.first));
			DivTable ret = new DivTable(this.symbol,
					this.dateAccessed,
					false, // not split adjusted.
					data);
			return ret;
		}

		// returns newly allocated thing.
		public SplitTable SplitTable()
		{
			SplitTable ret = new SplitTable();
			ret.symbol = this.symbol;
			ret.dateAccessed = this.dateAccessed;
			ret.data = new ArrayList<Single>();
			for (Pair p : this)
				if (0!=p.second)
					ret.data.add(new Single(p.date,p.second));
			return ret;
		}
	}

	public DivTable HistoricalDividends(String symbol, Date startDate, Date endDate)
		throws IOException
	{
		DivSplitTable dst = this.HistoricalDivSplits(symbol,startDate,endDate);
		return dst.DivTable();
	}

	public SplitTable HistoricalSplits(String symbol, Date startDate, Date endDate)
		throws IOException
	{
		DivSplitTable dst = this.HistoricalDivSplits(symbol, startDate, endDate);
		return dst.SplitTable();
	}

	// TODO refactor to make this private
	DivSplitTable HistoricalDivSplits(String symbol, Date startDate, Date endDate)
		throws IOException
	{
		DivSplitTable ret;
		synchronized(mDivSplits)
		{
			ret = mDivSplits.get(symbol);
			// TODO make this more sophisticated. As it is it's going to download the whole table another time every day. Lol.
			//
			// If we don't have the table or it is not in the bounds
			// of our current table
			if (null==ret || System.currentTimeMillis() - ret.dateAccessed > TwentyFourHours)
			{
				ret = HistoricalDivSplits(symbol);
				mDivSplits.put(symbol,ret);
			}
		}
		ret = ret.SubTable(startDate,endDate);
		return ret;

	}

	// Downloads historical split and dividend data and caches them. Called for side effects.
	private DivSplitTable HistoricalDivSplits(String symbol)
		throws IOException
	{
		UrlBuilder ub = new UrlBuilder(symbol,UrlBuilder.Type.DIVIDEND);
		ub.baseUrl = "http://ichart.yahoo.com/x?";
		String csv = IOUtils.fromStream(new URL(ub.toString()).openStream());
		String [] lines = csv.split("\n");
		//System.out.println(csv);

		DivSplitTable ret = new DivSplitTable(lines.length);
		ret.symbol = symbol;
		ret.dateAccessed = System.currentTimeMillis();

		//ret.data = new ArrayList<Pair>(lines.length);

		for (String s : lines)
		{
			s = s.replaceAll(" ","");
			String [] elements = s.split(",");
			if (elements[0].equals("DIVIDEND"))
			{
				Pair p = new Pair();
				int date = Integer.parseInt(elements[1]);
				p.date = new Date(date);
				p.second = 0;
				p.first = Double.parseDouble(elements[2]);
				ret.add(p);
			}
			if (elements[0].equals("SPLIT"))
			{
				Pair p = new Pair();
				int date = Integer.parseInt(elements[1]);
				p.date = new Date(date);
				p.first = 0;
				String [] fractionParts = elements[2].split(":"); // gonna be like 1:10 or something
				p.second = Double.parseDouble(fractionParts[1]) / Double.parseDouble(fractionParts[0]);
				ret.add(p);
			}
			if (elements[0].equals("STARTDATE"))
				ret.startDate = new Date(Integer.parseInt(elements[1]));
			if (elements[0].equals("ENDDATE"))
				ret.endDate = new Date(Integer.parseInt(elements[1]));
			if (elements[0].equals("STATUS"))
				ret.status = Integer.parseInt(elements[1]);
			if (elements[0].equals("TOTALSIZE"))
				ret.totalSize = Integer.parseInt(elements[1]);
		}

		//if (ret.totalSize != ret.data.size()) throw new RuntimeException("Uh oh");
		// TODO figure out what ret.totalSize represents

		Collections.reverse(ret); // ascending order

		return ret;
	}

	// Downloads stuff from Yahoo Finance
	public HistTable HistoricalPrices(String symbol)
		throws IOException
	{
		UrlBuilder ub = new UrlBuilder();
		ub.setSymbol(symbol);
		ub.setType(UrlBuilder.Type.DAILY);
		URL url = ub.toURL();
		String csv = IOUtils.fromStream(url.openStream());

		List<HistRow> list = new ArrayList<HistRow>();
		String [] lines = csv.split("\n");
		// skip first line and put it into a list backwards
		// since it is already in sorted descending order
		for (int i = lines.length; i--!=1; )
			list.add(new HistRow(lines[i]));

		HistTable ret = new HistTable(symbol,System.currentTimeMillis(),list);
		return ret;
	}


	final static long TwentyFourHours = 1000L * 60 * 60 * 24; // millis in day
	// choose better variable names
	public HistTable HistoricalPrices(String symbol, Date startDate, Date endDate)
		throws IOException
	{
		HistTable ret = null;
		synchronized (mTables)
		{
			ret = mTables.get(symbol);
			// TODO make this more sophisticated. As it is it's going to download the whole table another time every day. Lol.
			// Try something where you just download today's price. problematic
			// because then it's like mutable.
			//
			// If we don't have the table or it is old get a new one
			if (null==ret || System.currentTimeMillis() - ret.dateAccessed > TwentyFourHours)
			{
				ret = HistoricalPrices(symbol);
				mTables.put(symbol,ret);
			}
		}
		ret = ret.SubTable(startDate,endDate);
		return ret;
	}

	private YahooFinance() {}

	static final private YahooFinance sYahooFinance = new YahooFinance();
	static public YahooFinance getInstance()
	{
		return sYahooFinance;
	}

	// Assumes the second column is double.
	static public DataTable normalize(DataTable data)
	{
		DataTable ret = new DataTable(data);
		double mean = Statistics.mean(ret,1);
		double max = Statistics.max(ret,1);
		double max2 = Double.NEGATIVE_INFINITY;
		double min = Statistics.min(ret,1);
		double range = max - min;

		for (int i = 0; i < ret.getRowCount(); i++)
		{
			double val = (Double) ret.get(1, i);
			if (val > max2) max2 = val;
			val -= mean;
			val /= range;
			ret.set(1, i, val);
		}

		if (max!=max2) throw new RuntimeException("Uh oh"); // sanity check

		return ret;
	}

	static long time = 0;
	// helper function
	static public void logTime(String msg) {
		final long newTime = System.currentTimeMillis();
		final long elapsed = newTime - time;
		System.out.println(msg+" "+elapsed);
		time = newTime;
	}

	// This should really go in another class.
	public List<Pair> Alphabet(HistTable stock1, HistTable stock2, int windowSize, double convolutionFilter)
		throws IOException
	{
		logTime("Start");
		List<Pair> merged = FinUtils.merge(stock1,stock2);
		logTime("Merged");
		// This size is slightly different from R window size
		// it is like R window size + 1
		// Since Statistics.regress does the diff/log
		DataTable alpha = new DataTable(Integer.class, Double.class);
		DataTable beta = new DataTable(Integer.class, Double.class);
		// perform regression over rolling windows
		// TODO make this faster.
		SimpleRegression regress;
		for (int i = 0; i < merged.size() - windowSize; i++)
		{
			List<Pair> window = merged.subList(i,i+windowSize);
			regress = Statistics.regress(window);

			int date = window.get(window.size()-1).date.toInt();
			double intercept = regress.getIntercept();
			intercept = 100 * (Math.exp(intercept * 250) - 1); // in APR, not daily log
			alpha.add(date, intercept);
			beta.add(date, regress.getSlope());
		}

		logTime("Regressed");

		Kernel kernel = KernelUtils.getBinomial(convolutionFilter).normalize();
		DataSource filteredAlpha = new Convolution(alpha, kernel, Mode.REPEAT, 1);
		DataSource filteredBeta = new Convolution(beta ,kernel, Mode.REPEAT, 1);
		logTime("Smoothed");

		int size = filteredAlpha.getRowCount();
		List<Pair> ret = new ArrayList<Pair>(size);
		for (int i = 0; i < size; i++)
		{
			Pair p = new Pair(); // yay for hammering malloc ;)
			p.date = new Date((Integer)filteredAlpha.get(0, i)); // oy vey what awful code
			p.first = (Double) filteredAlpha.get(1,i);
			p.second = (Double) filteredBeta.get(1,i);
			ret.add(p);
		}
		return ret;
	}

	public static void main(String args[])
		throws IOException
	{
		YahooFinance yf = new YahooFinance();
		Date today = new Date(new java.util.Date());
		Date start = new Date(20000101);
		//yf.Alphabet("SPY","AAPL",100,.5);
		String symbol = "BAC";
		HistTable ht = yf.HistoricalPrices(symbol,start,today);
		ht = yf.HistoricalPrices(symbol,start,today);
		DivSplitTable dst = yf.HistoricalDivSplits(symbol,start,today);
		for (Single s : dst.DivTable().data)
		{
			System.out.println(((Date)s).toString()+" "+s.data);
		}
		HistTable adj = ht.AdjustOHLC();
		HistTable adj2 = ht.AdjustOHLCWithReinvestment();
		List<Pair> merged = FinUtils.merge(adj,adj2);

		for (int i = 0; i < adj.data.size(); ++i)
		{
			HistRow row = adj.data.get(i);
			System.out.println(row.date+","+adj.data.get(i).close
					+","+adj2.data.get(i).close
					+","+ht.data.get(i).close);
		}
	}
}



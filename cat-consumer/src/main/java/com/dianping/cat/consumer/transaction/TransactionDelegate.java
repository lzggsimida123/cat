package com.dianping.cat.consumer.transaction;

import java.util.Date;
import java.util.Map;

import org.unidal.lookup.annotation.Inject;
import org.unidal.lookup.annotation.Named;

import com.dianping.cat.Cat;
import com.dianping.cat.Constants;
import com.dianping.cat.config.AtomicMessageConfigManager;
import com.dianping.cat.config.server.ServerConfigManager;
import com.dianping.cat.config.server.ServerFilterConfigManager;
import com.dianping.cat.consumer.config.AllReportConfigManager;
import com.dianping.cat.consumer.transaction.model.entity.TransactionReport;
import com.dianping.cat.consumer.transaction.model.transform.DefaultNativeBuilder;
import com.dianping.cat.consumer.transaction.model.transform.DefaultNativeParser;
import com.dianping.cat.consumer.transaction.model.transform.DefaultSaxParser;
import com.dianping.cat.report.ReportDelegate;
import com.dianping.cat.task.TaskManager;
import com.dianping.cat.task.TaskManager.TaskProlicy;

import static com.dianping.cat.Constants.ALL;

@Named(type = ReportDelegate.class, value = TransactionAnalyzer.ID)
public class TransactionDelegate implements ReportDelegate<TransactionReport> {

	@Inject
	private TaskManager m_taskManager;

	@Inject
	private ServerFilterConfigManager m_configManager;

	@Inject
	private AllReportConfigManager m_transactionManager;

	@Inject
	private ServerConfigManager m_serverConfigManager;

	@Inject
	private AtomicMessageConfigManager m_atomicMessageConfigManager;

	private TransactionStatisticsComputer m_computer = new TransactionStatisticsComputer();

	@Override
	public void afterLoad(Map<String, TransactionReport> reports) {
	}

	@Override
	public void beforeSave(Map<String, TransactionReport> reports) {
		//		if (reports.size() > 0) {
		//			TransactionReport all = createAggregatedReport(reports);
		//
		//			reports.put(all.getDomain(), all);
		//		}
	}

	@Override
	public byte[] buildBinary(TransactionReport report) {
		return DefaultNativeBuilder.build(report);
	}

	@Override
	public String buildXml(TransactionReport report) {
		report.accept(m_computer);

		new TransactionReportCountFilter(m_serverConfigManager.getMaxTypeThreshold(),
								m_atomicMessageConfigManager.getMaxNameThreshold(report.getDomain()),
								m_serverConfigManager.getTypeNameLengthLimit()).visitTransactionReport(report);

		return report.toString();
	}

	public TransactionReport createAggregatedReport(Map<String, TransactionReport> reports) {
		if (reports.size() > 0) {
			TransactionReport first = reports.values().iterator().next();
			TransactionReport all = makeReport(ALL, first.getStartTime().getTime(), Constants.HOUR);
			TransactionReportTypeAggregator visitor = new TransactionReportTypeAggregator(all, m_transactionManager);

			try {
				for (TransactionReport report : reports.values()) {
					String domain = report.getDomain();

					if (!domain.equals(Constants.ALL)) {
						all.getIps().add(domain);

						visitor.visitTransactionReport(report);
					}
				}
			} catch (Exception e) {
				Cat.logError(e);
			}
			return all;
		} else {
			return new TransactionReport(ALL);
		}
	}

	@Override
	public boolean createHourlyTask(TransactionReport report) {
		String domain = report.getDomain();

		if (domain.equals(Constants.ALL) || m_configManager.validateDomain(domain)) {
			return m_taskManager
									.createTask(report.getStartTime(), domain, TransactionAnalyzer.ID,	TaskProlicy.ALL_EXCLUED_HOURLY);
		} else {
			return true;
		}
	}

	@Override
	public String getDomain(TransactionReport report) {
		return report.getDomain();
	}

	@Override
	public TransactionReport makeReport(String domain, long startTime, long duration) {
		TransactionReport report = new TransactionReport(domain);

		report.setStartTime(new Date(startTime));
		report.setEndTime(new Date(startTime + duration - 1));

		return report;
	}

	@Override
	public TransactionReport mergeReport(TransactionReport old, TransactionReport other) {
		TransactionReportMerger merger = new TransactionReportMerger(old);

		other.accept(merger);
		return old;
	}

	@Override
	public TransactionReport parseBinary(byte[] bytes) {
		return DefaultNativeParser.parse(bytes);
	}

	@Override
	public TransactionReport parseXml(String xml) throws Exception {
		TransactionReport report = DefaultSaxParser.parse(xml);

		return report;
	}
}

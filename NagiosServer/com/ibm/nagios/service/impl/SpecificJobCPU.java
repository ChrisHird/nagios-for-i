package com.ibm.nagios.service.impl;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import com.ibm.as400.access.AS400;
import com.ibm.nagios.service.Action;
import com.ibm.nagios.util.CommonUtil;
import com.ibm.nagios.util.JDBCConnection;
import com.ibm.nagios.util.Constants;

public class SpecificJobCPU implements Action {
    public SpecificJobCPU() {

    }

    public int execute(AS400 as400, Map<String, String> args, StringBuffer response) {
        int returnValue = Constants.UNKNOWN;
        double CPUPercentage = 0.;
        Statement stmt = null;
        ResultSet rs = null;
        String jobName = args.get("-J");
        if (jobName == null) {
            response.append("The argument -J [job name] is not set");
            return returnValue;
        }
        String warningCap = args.get("-W");
        String criticalCap = args.get("-C");
        double doubleWarningCap = (warningCap == null) ? 100 : Double.parseDouble(warningCap);
        double doubleCriticalCap = (criticalCap == null) ? 100 : Double.parseDouble(criticalCap);

        Connection connection = null;
        try {
            JDBCConnection JDBCConn = new JDBCConnection();
            connection = JDBCConn.getJDBCConnection(as400.getSystemName(), args.get("-U"), args.get("-P"), args.get("-SSL"));
            if (connection == null) {
                response.append(Constants.retrieveDataError + " - " + "Cannot get the JDBC connection");
                return returnValue;
            }
            stmt = connection.createStatement();
            //run the sql command first to set the base value
            rs = stmt.executeQuery("SELECT ELAPSED_CPU_PERCENTAGE FROM TABLE(QSYS2.ACTIVE_JOB_INFO('NO', '', '" + jobName + "', '')) X");
            //wait 5 seconds, run the SQL command second time to calculate the value of ELAPSED_CPU_PERCENTAG by interval
            Thread.sleep(5000);
            rs = stmt.executeQuery("SELECT ELAPSED_CPU_PERCENTAGE FROM TABLE(QSYS2.ACTIVE_JOB_INFO('NO', '', '" + jobName + "', '')) X");
            if (rs == null) {
                response.append(Constants.retrieveDataError + " - " + "Cannot retrieve data from server");
                return returnValue;
            }
            
            response.append(jobName + " is not active");
            while (rs.next()) {
                CPUPercentage = rs.getDouble("ELAPSED_CPU_PERCENTAGE");

                returnValue = CommonUtil.getStatus(CPUPercentage, doubleWarningCap, doubleCriticalCap, returnValue);
                response.setLength(0);
                response.append("Job: ").append(jobName).append(" CPU: ").append(CPUPercentage).append("% ").append(" | CPU = ").append(CPUPercentage).append("%;").append(doubleWarningCap).append(";").append(doubleCriticalCap).append("\n");
                return returnValue;
            }
        } catch (Exception e) {
            response.append(Constants.retrieveDataException + " - " + e.toString());
            CommonUtil.printStack(e.getStackTrace(), response);
            CommonUtil.logError(args.get("-H"), this.getClass().getName(), e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (rs != null)
                    rs.close();
                if (stmt != null)
                    stmt.close();
                if (connection != null)
                    connection.close();
            } catch (SQLException e) {
                response.append(Constants.retrieveDataException + " - " + e.toString());
                e.printStackTrace();
            }
        }
        return returnValue;
    }
}

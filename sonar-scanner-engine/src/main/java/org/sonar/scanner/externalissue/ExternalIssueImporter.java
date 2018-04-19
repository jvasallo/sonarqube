/*
 * SonarQube
 * Copyright (C) 2009-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.scanner.externalissue;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.TextPointer;
import org.sonar.api.batch.rule.Severity;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.issue.NewExternalIssue;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rules.RuleType;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.scanner.externalissue.ReportParser.Issue;
import org.sonar.scanner.externalissue.ReportParser.Location;
import org.sonar.scanner.externalissue.ReportParser.Report;

public class ExternalIssueImporter {
  private static final Logger LOG = Loggers.get(ExternalIssuesImportSensor.class);
  private static final int MAX_UNKNOWN_FILE_PATHS_TO_PRINT = 5;

  private final SensorContext context;
  private final Report report;
  private final Set<String> unknownFiles = new LinkedHashSet<>();
  private final Set<String> knownFiles = new LinkedHashSet<>();

  public ExternalIssueImporter(SensorContext context, Report report) {
    this.context = context;
    this.report = report;
  }

  public void execute() {
    int issueCount = 0;

    for (Issue issue : report.issues) {
      NewExternalIssue externalIssue = context.newExternalIssue()
        .forRule(RuleKey.of(issue.engineId, issue.ruleId))
        .severity(Severity.valueOf(issue.severity))
        .remediationEffortMinutes(20L)
        .type(RuleType.valueOf(issue.type));

      NewIssueLocation primary = fillLocation(context, externalIssue.newLocation(), issue.primaryLocation);
      if (primary != null) {
        knownFiles.add(issue.primaryLocation.filePath);
        externalIssue.at(primary);
        if (issue.secondaryLocations != null) {
          for (Location l : issue.secondaryLocations) {
            NewIssueLocation secondary = fillLocation(context, externalIssue.newLocation(), l);
            if (secondary != null) {
              externalIssue.addLocation(secondary);
            }
          }
        }
        issueCount++;
        externalIssue.save();
      } else {
        unknownFiles.add(issue.primaryLocation.filePath);
      }
    }

    LOG.info("Imported {} {} in {} {}", issueCount, pluralize("issue", issueCount), knownFiles.size(), pluralize("file", knownFiles.size()));
    int numberOfUnknownFiles = unknownFiles.size();
    if (numberOfUnknownFiles > 0) {
      LOG.info("External issues ignored for " + numberOfUnknownFiles + " unknown files, including: "
        + unknownFiles.stream().limit(MAX_UNKNOWN_FILE_PATHS_TO_PRINT).collect(Collectors.joining(", ")));
    }
  }

  private static String pluralize(String msg, int count) {
    if (count == 1) {
      return msg;
    }
    return msg + "s";
  }

  @CheckForNull
  private NewIssueLocation fillLocation(SensorContext context, NewIssueLocation newLocation, Location location) {
    InputFile file = findFile(context, location.filePath);
    if (file != null) {
      newLocation
        .message(location.message)
        .on(file);

      if (location.textRange != null) {
        if (location.textRange.startColumn != null) {
          TextPointer start = file.newPointer(location.textRange.startLine, location.textRange.startColumn);
          TextPointer end = file.newPointer(location.textRange.endLine, location.textRange.endColumn);
          newLocation.at(file.newRange(start, end));
        } else {
          newLocation.at(file.selectLine(location.textRange.startLine));
        }
      }
      return newLocation;
    }
    return null;
  }

  @CheckForNull
  private InputFile findFile(SensorContext context, String filePath) {
    return context.fileSystem().inputFile(context.fileSystem().predicates().hasPath(filePath));
  }

}
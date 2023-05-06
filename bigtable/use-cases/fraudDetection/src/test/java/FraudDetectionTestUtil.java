/*
 * Copyright 2022 Google LLC
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


import com.google.cloud.pubsub.v1.stub.GrpcSubscriberStub;
import com.google.cloud.pubsub.v1.stub.SubscriberStub;
import com.google.cloud.pubsub.v1.stub.SubscriberStubSettings;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.pubsub.v1.AcknowledgeRequest;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.PullResponse;
import com.google.pubsub.v1.ReceivedMessage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.apache.hadoop.hbase.shaded.org.apache.commons.io.IOUtils;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

public class FraudDetectionTestUtil {

  // Some IDs aren't known until the apply step. Do not parse these.
  protected static final String UNKNOWN_VALUE = "known after apply";

  // Make sure that the variable is set from running Terraform.
  public static void requireVar(String varName) {
    assertThat(varName).isNotNull();
  }

  // Make sure that the required environment variables are set before running the tests.
  public static String requireEnv(String varName) {
    String value = System.getenv(varName);
    assertWithMessage(String.format("Environment variable '%s' is required to perform these tests.",
        varName)).that(value).isNotNull();
    return value;
  }

  // Parse Terraform output and populate the variables needed for testing.
  private static void parseTerraformOutput(Process terraformProcess) throws IOException {
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(terraformProcess.getInputStream()));

    // Process terraform output.
    String line;
    while ((line = reader.readLine()) != null) {
      System.out.println(line);
      if (line.contains(UNKNOWN_VALUE)) {
        continue;
      } else if (line.contains("pubsub_input_topic = ")) {
        StreamingPipelineTest.pubsubInputTopic = line.split("\"")[1];
      } else if (line.contains("pubsub_output_topic = ")) {
        StreamingPipelineTest.pubsubOutputTopic = line.split("\"")[1];
      } else if (line.contains("pubsub_output_subscription = ")) {
        StreamingPipelineTest.pubsubOutputSubscription = line.split("\"")[1];
      } else if (line.contains("gcs_bucket = ")) {
        StreamingPipelineTest.gcsBucket = line.split("\"")[1];
      } else if (line.contains("cbt_instance = ")) {
        StreamingPipelineTest.cbtInstanceID = line.split("\"")[1];
      } else if (line.contains("cbt_table = ")) {
        StreamingPipelineTest.cbtTableID = line.split("\"")[1];
      }
    }
  }

  public static int runCommand(String command) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command.split(" ")).start();
    if (command.contains("apply")) {
      parseTerraformOutput(process);
    }

    int processResult = process.waitFor();
    if (processResult != 0) {
      String errorString = IOUtils.toString(process.getErrorStream(), StandardCharsets.UTF_8);
      assertThat(errorString).isEmpty();
    }
    return processResult;
  }

  // Returns all transactions in a file inside a GCS bucket.
  public static String[] getTransactions(String projectID, String gcsBucket, String filePath) {
    // Set StorageOptions for reading.
    StorageOptions options = StorageOptions.newBuilder()
        .setProjectId(projectID).build();

    Storage storage = options.getService();
    Blob blob = storage.get(gcsBucket, filePath);
    String fileContent = new String(blob.getContent());
    // return all transactions inside gcsBucket/filePath.
    return fileContent.split("\n");
  }

  public static SubscriberStub buildSubscriberStub() throws IOException {
    // Build Subscriber stub settings.
    SubscriberStubSettings subscriberStubSettings =
        SubscriberStubSettings.newBuilder()
            .setTransportChannelProvider(
                SubscriberStubSettings.defaultGrpcTransportProviderBuilder()
                    .setMaxInboundMessageSize(1 * 1024 * 1024) // 1MB (maximum message size).
                    .build())
            .build();
    return GrpcSubscriberStub.create(subscriberStubSettings);
  }

  // Read one message from subscriptionId, ack it and returns it.
  public static String readOneMessage(SubscriberStub subscriberStub, String projectId,
      String subscriptionId) throws IOException {
    String subscriptionName = ProjectSubscriptionName.format(projectId, subscriptionId);
    PullRequest pullRequest =
        PullRequest.newBuilder().setMaxMessages(1).setSubscription(subscriptionName).build();

    // Try to receive a message.
    ReceivedMessage receivedMessage = null;
    String payload = null;
    int numOfRetries = 20;
    while (receivedMessage == null && numOfRetries-- > 0) {
      PullResponse pullResponse = subscriberStub.pullCallable().call(pullRequest);
      if (pullResponse.getReceivedMessagesList().size() > 0) {
        receivedMessage = pullResponse.getReceivedMessagesList().get(0);
        payload = receivedMessage.getMessage().getData().toStringUtf8();
      }
    }

    // If no message is available, return null.
    if (receivedMessage == null) {
      return null;
    }

    // Ack the message.
    String ackId = receivedMessage.getAckId();
    AcknowledgeRequest acknowledgeRequest =
        AcknowledgeRequest.newBuilder()
            .setSubscription(subscriptionName)
            .addAckIds(ackId)
            .build();
    subscriberStub.acknowledgeCallable().call(acknowledgeRequest);
    return payload;
  }
}

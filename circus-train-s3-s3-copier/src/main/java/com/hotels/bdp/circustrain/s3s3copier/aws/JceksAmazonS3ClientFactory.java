/**
 * Copyright (C) 2016-2020 Expedia, Inc.
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
package com.hotels.bdp.circustrain.s3s3copier.aws;

import java.net.URI;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.AmazonS3URI;
import com.amazonaws.services.s3.model.Region;

import com.hotels.bdp.circustrain.api.conf.Security;
import com.hotels.bdp.circustrain.aws.AssumeRoleCredentialProvider;
import com.hotels.bdp.circustrain.aws.HadoopAWSCredentialProviderChain;
import com.hotels.bdp.circustrain.s3s3copier.S3S3CopierOptions;

@Component
public class JceksAmazonS3ClientFactory implements AmazonS3ClientFactory {

  private final static Logger LOG = LoggerFactory.getLogger(JceksAmazonS3ClientFactory.class);

  private final Security security;
  private final HiveConf conf;

  @Autowired
  public JceksAmazonS3ClientFactory(Security security, HiveConf replicaHiveConf) {
    this.security = security;
    conf = replicaHiveConf;
  }

  public JceksAmazonS3ClientFactory(Security security) {
    this(security, null);
  }

  @Override
  public AmazonS3 newInstance(AmazonS3URI uri, S3S3CopierOptions s3s3CopierOptions) {
    HadoopAWSCredentialProviderChain credentialProviderChain = getCredentialsProviderChain(
        s3s3CopierOptions.getAssumedRole(), s3s3CopierOptions.getAssumedRoleCredentialDuration());
    return newS3Client(uri, s3s3CopierOptions, credentialProviderChain);
  }

  private AmazonS3 newS3Client(
      AmazonS3URI uri,
      S3S3CopierOptions s3s3CopierOptions,
      HadoopAWSCredentialProviderChain credentialProviderChain) {
    LOG.debug("trying to get a client for uri '{}'", uri);
    AmazonS3 globalClient = newGlobalInstance(s3s3CopierOptions, credentialProviderChain);
    try {

      /*
       * When using roles it can take a while for the credentials to be retrieved from the
       * AssumeRoleCredentialsProvider. This can mean that the rest of the code completes before the credentials are
       * retrieved, resulting in errors. A temporary fix for this situation is to put the thread to sleep for 10s to
       * allow for retrieval before the code continues. Thread.sleep(10000);
       **/

      String bucketRegion = regionForUri(globalClient, uri);
      LOG.debug("Bucket region: {}", bucketRegion);
      return newInstance(bucketRegion, s3s3CopierOptions, credentialProviderChain);
    } catch (IllegalArgumentException e) {
      LOG.warn("Using global (non region specific) client", e);
      return globalClient;
    }
  }

  private String regionForUri(AmazonS3 client, AmazonS3URI uri) {
    String bucketRegion = client.getBucketLocation(uri.getBucket());
    Region region = Region.fromValue(bucketRegion);

    // S3 doesn't have a US East 1 region, US East 1 is really the region
    // US Standard. US Standard places the data in either an east coast
    // or west coast data center geographically closest to you.
    // SigV4 requires you to mention a region while signing a request
    // and for the S3's US standard endpoints the value to be used is "us-east-1"
    // US West 1 has an endpoint and so is treated as a stand alone region,
    // US East 1 doesn't and so is bundled into US Standard
    if (region.equals(Region.US_Standard)) {
      bucketRegion = "us-east-1";
    } else {
      bucketRegion = region.toString();
    }
    return bucketRegion;
  }

  private AmazonS3ClientBuilder applyClientConfigurations(AmazonS3ClientBuilder builder, S3S3CopierOptions s3s3CopierOptions) {
    ClientConfiguration clientConfiguration = new ClientConfiguration();
    clientConfiguration.withMaxConnections(s3s3CopierOptions.getMaxThreadPoolSize());
    return builder.withClientConfiguration(clientConfiguration);
  }

  private AmazonS3 newGlobalInstance(
      S3S3CopierOptions s3s3CopierOptions,
      HadoopAWSCredentialProviderChain credentialsChain) {
    AmazonS3ClientBuilder builder = AmazonS3ClientBuilder
        .standard()
        .withForceGlobalBucketAccessEnabled(Boolean.TRUE)
        .withCredentials(credentialsChain);

    applyClientConfigurations(builder, s3s3CopierOptions);

    URI s3Endpoint = s3s3CopierOptions.getS3Endpoint();
    if (s3Endpoint != null) {
      EndpointConfiguration endpointConfiguration = new EndpointConfiguration(s3Endpoint.toString(),
          Region.US_Standard.getFirstRegionId());
      builder.withEndpointConfiguration(endpointConfiguration);
    }
    return builder.build();
  }

  private AmazonS3 newInstance(
      String region,
      S3S3CopierOptions s3s3CopierOptions,
      HadoopAWSCredentialProviderChain credentialsChain) {
    AmazonS3ClientBuilder builder = AmazonS3ClientBuilder
        .standard()
        .withCredentials(credentialsChain);

    applyClientConfigurations(builder, s3s3CopierOptions);

    URI s3Endpoint = s3s3CopierOptions.getS3Endpoint(region);
    if (s3Endpoint != null) {
      EndpointConfiguration endpointConfiguration = new EndpointConfiguration(s3Endpoint.toString(), region);
      builder.withEndpointConfiguration(endpointConfiguration);
    } else {
      builder.withRegion(region);
    }

    return builder.build();
  }

  private HadoopAWSCredentialProviderChain getCredentialsProviderChain(String assumedRole, int assumedRoleDuration) {
    if (assumedRole != null) {
      LOG.debug("Creating credential chain for assuming role {}", assumedRole);
      return new HadoopAWSCredentialProviderChain(createNewConf(conf, assumedRole, assumedRoleDuration));
    } else if (security.getCredentialProvider() != null) {
      LOG.debug("Creating credential chain with Jceks - cred provider {}", security.getCredentialProvider());
      return new HadoopAWSCredentialProviderChain(security.getCredentialProvider());
    }
    LOG.debug("Creating EC2ContainerCredentialsProviderWrapper provider chain");
    return new HadoopAWSCredentialProviderChain();
  }

  private Configuration createNewConf(Configuration config, String assumedRole, int assumedRoleDuration) {
    Configuration conf = new Configuration(config);
    conf.addResource(AssumeRoleCredentialProvider.ASSUME_ROLE_PROPERTY_NAME);
    conf.set(AssumeRoleCredentialProvider.ASSUME_ROLE_PROPERTY_NAME, assumedRole);
    conf.setInt(AssumeRoleCredentialProvider.ASSUME_ROLE_SESSION_DURATION_SECONDS_PROPERTY_NAME, assumedRoleDuration);
    return conf;
  }

}

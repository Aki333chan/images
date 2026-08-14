import { Global, Module } from '@nestjs/common';
import { ApplicationApiService } from './application-api.service';
import { ClientApiService } from './client-api.service';
import { PteroSecretsService } from './ptero-secrets.service';

@Global()
@Module({
  providers: [PteroSecretsService, ApplicationApiService, ClientApiService],
  exports: [PteroSecretsService, ApplicationApiService, ClientApiService],
})
export class PterodactylModule {}

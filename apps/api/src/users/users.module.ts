import { Module } from '@nestjs/common';
import { UsersController } from './users.controller';
import { UsersService } from './users.service';
import { AccountProvisioningService } from './account-provisioning.service';
import { OnboardingService } from './onboarding.service';

@Module({
  controllers: [UsersController],
  providers: [UsersService, AccountProvisioningService, OnboardingService],
  exports: [OnboardingService],
})
export class UsersModule {}
